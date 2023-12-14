/*
 * Copyright 2020-2023 Jeroen van den Bos & Vincent van der Meer
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.parsingdata.jpegfragments.validator.jpeg;

import static io.parsingdata.jpegfragments.validator.jpeg.HuffmanTable.CoefficientType.AC;
import static io.parsingdata.jpegfragments.validator.jpeg.HuffmanTable.CoefficientType.DC;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.DRI;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.FOOTER;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HEADER;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HT;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.IDENTIFIER;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SOF;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SOF0_IDENTIFIER;
import static io.parsingdata.metal.Shorthand.con;
import static io.parsingdata.metal.Shorthand.eq;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.ref;

import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.parsingdata.jpegfragments.Validator;
import io.parsingdata.jpegfragments.validator.jpeg.HuffmanTable.CoefficientType;
import io.parsingdata.metal.data.ByteStream;
import io.parsingdata.metal.data.Environment;
import io.parsingdata.metal.data.ImmutableList;
import io.parsingdata.metal.data.ParseState;
import io.parsingdata.metal.data.callback.Callback;
import io.parsingdata.metal.data.callback.Callbacks;
import io.parsingdata.metal.encoding.Encoding;
import io.parsingdata.metal.expression.value.Value;
import io.parsingdata.metal.token.Token;

public class JpegValidator implements Validator, Callback {

    BigInteger reportedOffset;
    final Map<CoefficientType, Map<Integer, HuffmanTable>> huffmanTables = new HashMap<>();
    static final List<String> CHANNEL_NAME = List.of("Blueness", "Redness");

    public static int[] listToIntArray(final ImmutableList<Value> list) {
        final int[] result = new int[(int)list.size];
        ImmutableList<Value> current = list;
        for (int i = 0; !current.isEmpty(); i++) {
            result[i] = current.head.asNumeric().intValueExact();
            current = current.tail;
        }
        return result;
    }

    @Override
    public JpegValidationResult validate(ByteStream input) throws IOException {
        final Optional<ParseState> headerResult = parseJpegHeader(input);
        if (headerResult.isEmpty()) {
            return new JpegValidationResult(false, this.reportedOffset, this, "JpegHeader");
        }
        final JpegValidationResult mcuValidationResult = isBaseline(headerResult.get()) ?
            JpegBaseline.validateBaselineScan(this, headerResult.get(), input) :
            new JpegProgressive().validateProgressiveScans(this, headerResult.get(), input);
        if (!mcuValidationResult.completed) {
            return mcuValidationResult;
        }
        final Optional<ParseState> footerResult = FOOTER.parse(new Environment(ParseState.createFromByteStream(input, mcuValidationResult.offset), Callbacks.create().add(this), Encoding.DEFAULT_ENCODING));
        return footerResult.map(parseState -> new JpegValidationResult(true, parseState.offset, this, "")).orElseGet(() -> new JpegValidationResult(false, mcuValidationResult.offset, this, "JpegFooter"));
    }

    private Optional<ParseState> parseJpegHeader(final ByteStream input) {
        this.reportedOffset = BigInteger.ZERO;
        huffmanTables.put(DC, new HashMap<>());
        huffmanTables.put(AC, new HashMap<>());
        return HEADER.parse(
            new Environment(ParseState.createFromByteStream(input),
                Callbacks.create()
                    .add(this)
                    .add(HT, new Callback() {
                        @Override public void handleSuccess(final Token token, final ParseState before, final ParseState after) {
                            final HuffmanTable table = new HuffmanTable(after);
                            huffmanTables.get(table.type).put(table.id, table);
                        }
                        @Override public void handleFailure(final Token token, final ParseState before) {}
                    })
                    .add(DRI, new Callback() {
                            @Override
                            public void handleSuccess(Token token, ParseState before, ParseState after) {
                            }
                            @Override public void handleFailure(Token token, ParseState before) {}
                        }
                    ),
                Encoding.DEFAULT_ENCODING));
    }

    private boolean isBaseline(final ParseState headerState) {
        return eq(last(ref(SOF + Token.SEPARATOR + IDENTIFIER)), con(SOF0_IDENTIFIER)).eval(headerState, Encoding.DEFAULT_ENCODING);
    }

    @Override
    public void handleSuccess(final Token token, final ParseState before, final ParseState after) {
        if (token.name.equals(IDENTIFIER)) {
            this.reportedOffset = after.offset;
        }
    }

    @Override
    public void handleFailure(Token token, ParseState before) {}

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
