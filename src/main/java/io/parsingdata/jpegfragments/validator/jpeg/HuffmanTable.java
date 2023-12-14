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
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.TABLE_CLASS_TABLE_HUFFMAN_IDENTIFIER;
import static io.parsingdata.metal.Shorthand.con;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.ref;
import static io.parsingdata.metal.Shorthand.scope;
import static io.parsingdata.metal.data.Selection.reverse;

import java.math.BigInteger;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import io.parsingdata.metal.data.ImmutableList;
import io.parsingdata.metal.data.ParseState;
import io.parsingdata.metal.encoding.Encoding;
import io.parsingdata.metal.expression.value.Value;

public class HuffmanTable {

    public enum CoefficientType {
        AC, DC
    }

    public final CoefficientType type;
    public final int id;
    public final LinkedHashMap<SizedBitSet, Integer> codeSymbolMap;
    public final int maxCodeLength;

    public HuffmanTable(final ParseState parseState) {
        final ImmutableList<Value> listOfLengths = scope(ref("li"), con(0)).eval(parseState, Encoding.DEFAULT_ENCODING);
        final ImmutableList<Value> listOfSymbols = scope(ref("vij"), con(0)).eval(parseState, Encoding.DEFAULT_ENCODING);
        final Value tcth = last(ref(TABLE_CLASS_TABLE_HUFFMAN_IDENTIFIER)).evalSingle(parseState, Encoding.DEFAULT_ENCODING).get();
        this.type = tcth.asNumeric().compareTo(BigInteger.valueOf(15)) > 0 ? AC : DC;
        this.id = tcth.asNumeric().intValueExact() & 0x0F;
        ImmutableList<Value> lengths = reverse(listOfLengths);
        ImmutableList<Value> symbols = reverse(listOfSymbols);
        codeSymbolMap = new LinkedHashMap<>();
        int codeLength = 1;
        int codeCandidate = 0;
        int lastNonZeroCodeLength = 0;
        while (lengths.size > 0) {
            if (lengths.head.asNumeric().compareTo(BigInteger.ZERO) != 0) {
                lastNonZeroCodeLength = codeLength;
                for (int i = 0; i < lengths.head.asNumeric().intValueExact(); i++) {
                    final int symbolValue = symbols.head.asNumeric().intValueExact();
                    final BitSet code = new BitSet(codeLength);
                    for(int j = 0; j < codeLength; j++) {
                        code.set(codeLength - j - 1, ((codeCandidate >> j) & 1) == 1);
                    }
                    codeSymbolMap.put(new SizedBitSet(codeLength, code), symbolValue);
                    symbols = symbols.tail;
                    codeCandidate++;
                }
            }
            codeCandidate<<=1;
            codeLength++;
            lengths = lengths.tail;
        }
        maxCodeLength = lastNonZeroCodeLength;
    }

    public Optional<MatchResult> findShortestMatch(final BitSet maxCodeLengthData) {
        for (Map.Entry<SizedBitSet, Integer> entry : codeSymbolMap.entrySet()) {
            if (maxCodeLengthData.get(0, entry.getKey().length).equals(entry.getKey().bitSet)) {
                return Optional.of(new MatchResult(entry.getKey().length, entry.getValue()));
            }
        }
        return Optional.empty();
    }

}
