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

import static java.math.BigInteger.ONE;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;

public class JpegByteStream {

    private final ByteStream input;
    private int offset;

    public JpegByteStream(final ByteStream input, final int offset) {
        this.input = input;
        this.offset = offset;
    }

    public int getOffset() { return this.offset; }

    public Optional<UnescapedByteArray> peek(final int count) throws IOException {
        final byte[] byteValues = new byte[count];
        byte previousValue = 0;
        if (this.offset > 0) {
            if (!this.input.isAvailable(BigInteger.valueOf(this.offset-1), ONE)) {
                return Optional.empty();
            }
            previousValue = this.input.read(BigInteger.valueOf(this.offset-1), 1)[0];
        }
        int skippedBytes = 0;
        for (int i = 0; i < count + skippedBytes; i++) {
            if (!this.input.isAvailable(BigInteger.valueOf(this.offset+i), ONE)) {
                return Optional.empty();
            }
            byte currentValue = this.input.read(BigInteger.valueOf(this.offset+i), 1)[0];
            if (previousValue == -1 && (currentValue == 0 || currentValue == -1)) { // We found a case of byte stuffing
                skippedBytes++;
                //System.out.println("Encountered byte stuffing at offset " + (getOffset() + i + skippedBytes));
            } else {
                byteValues[i-skippedBytes] = currentValue;
            }
            previousValue = currentValue;
        }
        return Optional.of(new UnescapedByteArray(byteValues, count+skippedBytes));
    }

    public boolean skip(final int count) throws IOException {
        Optional<UnescapedByteArray> dataRead = peek(count);
        if (dataRead.isEmpty()) { return false; }
        final int skipLength = dataRead.get().totalLength;
        if (this.input.isAvailable(BigInteger.valueOf(this.offset), BigInteger.valueOf(skipLength))) {
            this.offset += skipLength;
            return true;
        }
        return false;
    }

}
