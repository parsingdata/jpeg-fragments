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
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.DC_AC_TABLE_SELECTOR;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HEIGHT;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.RESTART_INTERVAL;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SOS;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.WIDTH;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegValidator.CHANNEL_NAME;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegValidator.listToIntArray;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.ref;
import static io.parsingdata.metal.Shorthand.rev;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

import java.io.IOException;
import java.math.BigInteger;
import java.util.BitSet;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;
import io.parsingdata.metal.data.Environment;
import io.parsingdata.metal.data.ParseState;
import io.parsingdata.metal.data.callback.Callbacks;
import io.parsingdata.metal.encoding.Encoding;

public class JpegBaseline {

    private static String info = "";

    private JpegBaseline() {}

    static JpegValidationResult validateBaselineScan(final JpegValidator validator, final ParseState headerState, final ByteStream input) throws IOException {
        final Optional<ParseState> scanResult = SOS.parse(new Environment(ParseState.createFromByteStream(input, headerState.offset), Callbacks.create().add(validator), Encoding.DEFAULT_ENCODING));
        if (scanResult.isEmpty()) {
            info = "SOSBlock";
            return new JpegValidationResult(false, validator.reportedOffset, validator, info);
        }
        return validateBaselineMcus(validator, headerState, scanResult.get(), input);
    }

    private static JpegValidationResult validateBaselineMcus(final JpegValidator validator, final ParseState headerState, final ParseState scanState, final ByteStream input) throws IOException {
        final int height = last(ref(HEIGHT)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
        final int width = last(ref(WIDTH)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
        final int totalChannelCount = rev(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME)).eval(headerState, Encoding.DEFAULT_ENCODING).head.asNumeric().intValueExact();
        final byte samplingFactors = rev(ref("sampling_factors")).eval(headerState, Encoding.DEFAULT_ENCODING).head.value()[0];
        final int mcuWidthFactor = totalChannelCount > 1 ? (samplingFactors >> 4) & 0x0F : 1; // If there's only one channel, it's grayscale, which implies no subsampling
        final int mcuWidth = ((width  / (8 * mcuWidthFactor)) + (width  % (8 * mcuWidthFactor) == 0 ? 0 : 1));
        final int mcuHeightFactor = totalChannelCount > 1 ? samplingFactors & 0x0F : 1; // If there's only one channel, it's grayscale, which implies no subsampling
        final int mcuHeight = ((height / (8 * mcuHeightFactor)) + (height % (8 * mcuHeightFactor) == 0 ? 0 : 1));
        final int mcuCount = mcuHeight * mcuWidth;
        final int luminanceCountPerMcu = mcuWidthFactor * mcuHeightFactor; // Calculate the ratio of luminance vs. chrominance values with subsampling
        final int restartInterval = last(ref(RESTART_INTERVAL)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).map(value -> value.asNumeric().intValueExact()).orElse(0);
        validator.reportedOffset = scanState.offset;
        final JpegEntropyCodedBitStream bitStream = new JpegEntropyCodedBitStream(input, validator.reportedOffset, 0);
        final int[] tableSelectors = listToIntArray(rev(ref(DC_AC_TABLE_SELECTOR)).eval(scanState, Encoding.DEFAULT_ENCODING));
        for (int mcuIndex = 0; mcuIndex < mcuCount; mcuIndex++) {
            // Restart marker:
            if (!validateRestartMarker(bitStream, mcuIndex, restartInterval)) {
                return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
            }
            // Redness and blueness channel:
            for (int channelIndex = 0; channelIndex < totalChannelCount; channelIndex++) {
                if (channelIndex == 0) {
                    // Luminance channel:
                    for (int luminanceIndex = 0; luminanceIndex < luminanceCountPerMcu; luminanceIndex++) {
                        if (!validateQuantizationArray("Luminance", bitStream, validator.huffmanTables.get(DC).get(tableSelectors[channelIndex] >>> 4), validator.huffmanTables.get(AC).get(tableSelectors[channelIndex] & 0x0F), mcuIndex, mcuWidth)) {
                            return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                        }
                    }
                } else {
                    if (!validateQuantizationArray(CHANNEL_NAME.get(channelIndex-1), bitStream, validator.huffmanTables.get(DC).get(tableSelectors[channelIndex] >>> 4), validator.huffmanTables.get(AC).get(tableSelectors[channelIndex] & 0x0F), mcuIndex, mcuWidth)) {
                        return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                    }
                }
            }
            validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
        }
        if (validateRestartMarker(bitStream, mcuCount, restartInterval)) {
            validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
            info = "";
        }
        return new JpegValidationResult(true, validator.reportedOffset, validator, info);
    }

    private static boolean validateRestartMarker(JpegEntropyCodedBitStream input, int mcuIndex, int restartInterval) throws IOException {
        if (mcuIndex > 0 && restartInterval > 0 && (mcuIndex % restartInterval) == 0) { // If restartInterval == 0, there are no markers.
            if (input.getBitOffset() > 0) { // Align to next byte boundary.
                input.skip(8 - input.getBitOffset());
            }
            final Optional<BitSet> restartMarkerValue = input.peek(16);
            if (restartMarkerValue.isEmpty()) {
                info = "RestartM";
                return false;
            }
            final byte[] restartMarkerConvertedBits = restartMarkerValue.get().toByteArray();
            final byte[] restartMarkerBytes = new byte[2];
            if (restartMarkerConvertedBits.length > 0) { restartMarkerBytes[0] = restartMarkerConvertedBits[0]; }
            if (restartMarkerConvertedBits.length > 1) { restartMarkerBytes[1] = restartMarkerConvertedBits[1]; }
            // Integer.reverse() with >> 24 and & 0xff is used because BitSet provides the bytes in reversed order.
            if (restartMarkerBytes[0] != -1 || (byte)((Integer.reverse(restartMarkerBytes[1]) >> 24) & 0xff) != (byte)(208 + (((mcuIndex / restartInterval) - 1) % 8) & 0xff)) {
                info = "RestartM";
                return false;
            }
            input.skip(16);
        }
        return true;
    }

    private static boolean validateQuantizationArray(final String channelName, final JpegEntropyCodedBitStream input, final HuffmanTable dcTable, final HuffmanTable acTable, final int mcuIndex, final int mcuWidth) throws IOException {
        int quantizationArraySize = 0; // This counter will count to 63 as the array fills up.
        final Optional<BitSet> maxDCCodeLengthData = input.peek(dcTable.maxCodeLength);
        if (maxDCCodeLengthData.isEmpty()) {
            info = "EOF";
            return false;
        }
        final Optional<MatchResult> matchDCResult = dcTable.findShortestMatch(maxDCCodeLengthData.get());
        if (matchDCResult.isEmpty()) {
            info = "Huffman-DC; " + channelName;
            return false; // No Huffmancode match found: this is a Huffmantable lookup error.
        }
        // nr. 0: DC, nr. 1 t/m max. 63: AC.
        quantizationArraySize++;
        input.skip(matchDCResult.get().bitsMatched + matchDCResult.get().symbol);
        while (quantizationArraySize < 64) {
            final Optional<BitSet> maxACCodeLengthData = input.peek(acTable.maxCodeLength);
            if (maxACCodeLengthData.isEmpty()) {
                info = "EOF";
                return false;
            }
            final Optional<MatchResult> matchACResult = acTable.findShortestMatch(maxACCodeLengthData.get());
            if (matchACResult.isEmpty()) {
                info = "Huffman-AC; " + channelName;
                return false; // No Huffmancode match found: this is a Huffmantable lookup error.
            } else {
                if (matchACResult.get().symbol == 0) {
                    quantizationArraySize = 64;
                } else {
                    final int higherNibbleValue = (matchACResult.get().symbol & 0x00F0) >> 4;
                    quantizationArraySize += higherNibbleValue;

                   final int lowerNibbleValue = matchACResult.get().symbol & 0x000F;
                    quantizationArraySize++;
                    if (quantizationArraySize > 64) {
                        info = "QASize; " + channelName;
                        return false; // Quantization Array Size overflow found.
                    }
                    input.skip(lowerNibbleValue);
                }
                input.skip(matchACResult.get().bitsMatched);
            }
        }
        return true;
    }

}
