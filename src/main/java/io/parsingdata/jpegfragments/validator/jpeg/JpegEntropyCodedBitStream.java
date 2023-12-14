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
import static java.math.BigInteger.ZERO;

import java.io.IOException;
import java.math.BigInteger;
import java.util.BitSet;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;

public class JpegEntropyCodedBitStream {

    private final JpegByteStream input;
    private int bitOffset;

    public JpegEntropyCodedBitStream(final ByteStream input, final BigInteger byteOffset, final int bitOffset) {
        if (!input.isAvailable(byteOffset, bitOffset > 0 ? ONE : ZERO)) {
            throw new RuntimeException("EOF error.");
        }
        this.input = new JpegByteStream(input, byteOffset.intValueExact());
        this.bitOffset = bitOffset;
    }

    public int getOffset() throws IOException {
        int sizeToCheck = 1 + (getBitOffset() > 0 ? 1 : 0);
        final Optional<UnescapedByteArray> peekedValue = input.peek(sizeToCheck);
        if (peekedValue.isEmpty() || peekedValue.get().totalLength == sizeToCheck) {
            return this.input.getOffset();
        } else {
            // If a value was returned, we are not at EOF, but if more bytes we need than requested, we need to skip the escaped values.
            return this.input.getOffset() + (peekedValue.get().totalLength - sizeToCheck);
        }
    }
    public int getBitOffset() { return bitOffset; }

    public Optional<BitSet> peek(final int count) throws IOException {
        final int bytesNeeded = (count+bitOffset) / 8 + ((count+bitOffset) % 8 > 0 ? 1 : 0);
        final Optional<UnescapedByteArray> dataRead = this.input.peek(bytesNeeded);
        if (dataRead.isEmpty()) { return Optional.empty(); }
        final byte[] byteValues = dataRead.get().byteValues;
        final BitSet requestedBits = new BitSet(count);
        int localBitOffset = bitOffset; // localBitOffset needed for 'peek' -> don't change the real offset
        for (int byteIndex = 0, requestedIndex = 0; byteIndex < bytesNeeded; byteIndex++) {
            for (int bitIndex = localBitOffset; bitIndex < 8 && requestedIndex < count; bitIndex++, requestedIndex++) {
                requestedBits.set(requestedIndex, ((byteValues[byteIndex] >> (7 - bitIndex)) & 1) == 1);
            }
            localBitOffset = 0;
        }
        return Optional.of(requestedBits);
    }

    public boolean skip(final int bits) throws IOException {
        int newBitOffset = this.bitOffset;
        int byteIncrease = bits / 8;
        int bitIncrease = bits % 8;
        if (newBitOffset + bitIncrease >= 8) {
            byteIncrease++;
            bitIncrease -= 8;
        }
        newBitOffset += bitIncrease;
        final boolean dataAvailable = byteIncrease == 0 || this.input.skip(byteIncrease);
        if (dataAvailable) {
            this.bitOffset = newBitOffset;
        }
        return dataAvailable;
    }

}
