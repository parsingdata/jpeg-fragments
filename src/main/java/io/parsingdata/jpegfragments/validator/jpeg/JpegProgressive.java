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
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.END_OF_SPECTRAL_SELECTION;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HEIGHT;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HIGH_AND_LOW_SUCCESSIVE_APPROXIMATION_BIT_POSITION;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.HT;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.NUMBER_OF_IMAGE_COMPONENTS_IN_SCAN;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.RESTART_INTERVAL;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SCAN;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.SCAN_COMPONENT_SELECTOR;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.START_OF_SPECTRAL_SELECTION;
import static io.parsingdata.jpegfragments.validator.jpeg.JpegStructure.WIDTH;
import static io.parsingdata.metal.Shorthand.con;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.nth;
import static io.parsingdata.metal.Shorthand.ref;
import static io.parsingdata.metal.Shorthand.rev;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Optional;

import io.parsingdata.metal.data.ByteStream;
import io.parsingdata.metal.data.Environment;
import io.parsingdata.metal.data.ParseState;
import io.parsingdata.metal.data.callback.Callback;
import io.parsingdata.metal.data.callback.Callbacks;
import io.parsingdata.metal.encoding.Encoding;
import io.parsingdata.metal.expression.value.Value;
import io.parsingdata.metal.token.Token;

public class JpegProgressive {

    private static String[] channelNames = {"Luminance", "Blueness", "Redness"};
    private String info = "";
    private int endOfBandSkips;

    JpegProgressive() {}

    JpegValidationResult validateProgressiveScans(final JpegValidator validator, final ParseState headerState, final ByteStream input) throws IOException {
        this.endOfBandSkips = 0;
        final Optional<Value> heightValue = last(ref(HEIGHT)).evalSingle(headerState, Encoding.DEFAULT_ENCODING);
        if (heightValue.isEmpty()) {
            info = "JpegHeader";
            return new JpegValidationResult(false, validator.reportedOffset, validator, info);
        }
        final int height = heightValue.get().asNumeric().intValueExact();
        final Optional<Value> widthValue = last(ref(WIDTH)).evalSingle(headerState, Encoding.DEFAULT_ENCODING);
        if (widthValue.isEmpty()) {
            info = "JpegHeader";
            return new JpegValidationResult(false, validator.reportedOffset, validator, info);
        }
        final int width = widthValue.get().asNumeric().intValueExact();
        final int mcuBaseWidth = ((width  / 8) + (width  % 8 == 0 ? 0 : 1));
        final int mcuBaseHeigth = ((height / 8) + (height % 8 == 0 ? 0 : 1));
        final int mcuBaseCount = mcuBaseWidth * mcuBaseHeigth;

        final int totalChannelCount = rev(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME)).eval(headerState, Encoding.DEFAULT_ENCODING).head.asNumeric().intValueExact();

        final byte samplingFactors = rev(ref("sampling_factors")).eval(headerState, Encoding.DEFAULT_ENCODING).head.value()[0];
        final int[] componentIDs = JpegValidator.listToIntArray(rev(ref("component_identifier")).eval(headerState, Encoding.DEFAULT_ENCODING));
        final int mcuWidthFactor = totalChannelCount > 1 ? (samplingFactors >> 4) & 0x0F : 1; // If there's only one channel, it's grayscale, which implies no subsampling
        final int mcuWidth = ((width  / (8 * mcuWidthFactor)) + (width  % (8 * mcuWidthFactor) == 0 ? 0 : 1));
        final int mcuHeightFactor = totalChannelCount > 1 ? samplingFactors & 0x0F : 1; // If there's only one channel, it's grayscale, which implies no subsampling
        final int mcuHeight = ((height / (8 * mcuHeightFactor)) + (height % (8 * mcuHeightFactor) == 0 ? 0 : 1));
        final int mcuCount = mcuHeight * mcuWidth;
        final boolean skipHorizontal = (mcuWidthFactor > 1) && ((width % (mcuWidthFactor * 8)) <= 8) && ((width % (mcuWidthFactor * 8)) > 0);
        final boolean skipVertical = (mcuHeightFactor > 1) && ((height % (mcuHeightFactor * 8)) <= 8) && ((height % (mcuHeightFactor * 8)) > 0);
        final int luminanceCountPerMcu = mcuWidthFactor * mcuHeightFactor; // Calculate the ratio of luminance vs. chrominance values with subsampling

        int restartInterval = last(ref(RESTART_INTERVAL)).evalSingle(headerState, Encoding.DEFAULT_ENCODING).map(value -> value.asNumeric().intValueExact()).orElse(0);

        final int[][] previousSaLows = new int[totalChannelCount][64];
        for (int[] previousSaLow : previousSaLows) {
            Arrays.fill(previousSaLow, -1);
        }
        boolean finalScanCompleted = false;
        validator.reportedOffset = headerState.offset;
        final boolean[][][] refinableCoeffs = new boolean[totalChannelCount][][];
        refinableCoeffs[0] = new boolean[mcuBaseCount][64];
        for (int chromIndex = 1; chromIndex < totalChannelCount; chromIndex++) {
            refinableCoeffs[chromIndex] = new boolean[mcuCount][64];
        }
        while (!finalScanCompleted) {
            final Optional<ParseState> scanResult = SCAN.parse(new Environment(ParseState.createFromByteStream(input, validator.reportedOffset), Callbacks.create().add(validator).add(HT, new Callback() {
                @Override public void handleSuccess(final Token token, final ParseState before, final ParseState after) {
                    final HuffmanTable table = new HuffmanTable(after);
                    validator.huffmanTables.get(table.type).put(table.id, table);
                }
                @Override public void handleFailure(final Token token, final ParseState before) {}
            }), Encoding.DEFAULT_ENCODING));
            if (scanResult.isEmpty()) {
                info = "SOSBlock";
                return new JpegValidationResult(false, validator.reportedOffset, validator, info);
            }
            validator.reportedOffset = scanResult.get().offset;
            restartInterval = last(ref(RESTART_INTERVAL)).evalSingle(scanResult.get(), Encoding.DEFAULT_ENCODING).map(value -> value.asNumeric().intValueExact()).orElse(restartInterval);
            final int startOfSpectralSelection = last(ref(START_OF_SPECTRAL_SELECTION)).evalSingle(scanResult.get(), Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
            final int endOfSpectralSelection = last(ref(END_OF_SPECTRAL_SELECTION)).evalSingle(scanResult.get(), Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
            final int imageComponentsInScan = last(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_SCAN)).evalSingle(scanResult.get(), Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
            final int[] scanComponentSelectors = JpegValidator.listToIntArray(rev(ref(SCAN_COMPONENT_SELECTOR)).eval(scanResult.get(), Encoding.DEFAULT_ENCODING));
            final int saValues = last(ref(HIGH_AND_LOW_SUCCESSIVE_APPROXIMATION_BIT_POSITION)).evalSingle(scanResult.get(), Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact();
            final int saLow = saValues & 0x0f;
            final int saHigh = saValues >>> 4;
            if (startOfSpectralSelection > endOfSpectralSelection ||
                endOfSpectralSelection > 63 ||
                (startOfSpectralSelection == 0 && endOfSpectralSelection != 0) ||
                (startOfSpectralSelection > 0 && imageComponentsInScan != 1) ||
                (saHigh != 0 && saLow != (saHigh - 1))) {
                info = "SOSBlock";
                return new JpegValidationResult(false, validator.reportedOffset, validator, info);
            }

            final JpegValidationResult result = validateProgressiveScanData(componentIDs, luminanceCountPerMcu, restartInterval, mcuWidth, mcuHeight, mcuWidthFactor, mcuHeightFactor, mcuBaseCount, skipHorizontal, skipVertical, startOfSpectralSelection, endOfSpectralSelection, saLow, saHigh, imageComponentsInScan, scanComponentSelectors, refinableCoeffs, validator, headerState, scanResult.get(), input);
            if (!result.completed) {
                return result;
            }
            if (imageComponentsInScan == 1) {
                for (int ssIndex = startOfSpectralSelection; ssIndex <= endOfSpectralSelection; ssIndex++) {
                    previousSaLows[getComponentIndex(componentIDs, scanComponentSelectors[0])][ssIndex] = saLow;
                }
            } else {
                for (int channelIndex = 0; channelIndex < previousSaLows.length; channelIndex++) {
                    previousSaLows[channelIndex][0] = saLow;
                }
            }
            finalScanCompleted = finalScanCompleted(previousSaLows);
            validator.reportedOffset = result.offset;
        }
        return new JpegValidationResult(true, validator.reportedOffset, validator, info);
    }

    private int getComponentIndex(final int[] componentIDs, final int scanComponentSelector) {
        for (int i = 0; i < componentIDs.length; i++) {
            if (componentIDs[i] == scanComponentSelector) { return i; }
        }
        throw new RuntimeException("provided scanComponentSelector not specified in Frame header.");
    }

    private boolean finalScanCompleted(final int[][] previousSaLows) {
        for (int[] saLows : previousSaLows) {
            for (int saLow : saLows) {
                if (saLow != 0) { return false; }
            }
        }
        return true;
    }

    private JpegValidationResult validateProgressiveScanData(final int[] componentIDs, final int luminanceCountPerMcu, final int restartInterval, final int mcuWidth, final int mcuHeight, final int mcuWidthFactor, final int mcuHeightFactor, final int mcuBaseCount, final boolean skipHorizontal, final boolean skipVertical, final int startOfSpectralSelection, final int endOfSpectralSelection, final int saLow, final int saHigh, final int imageComponentsInScan, final int[] scanComponentSelectors, final boolean[][][] myRefinableCoeffs, final JpegValidator validator, final ParseState headerState, final ParseState scanState, final ByteStream input) throws IOException {
        validator.reportedOffset = scanState.offset;
        final JpegEntropyCodedBitStream bitStream = new JpegEntropyCodedBitStream(input, validator.reportedOffset, 0);
        if (saHigh == 0) { // DC or AC first
            if (startOfSpectralSelection == 0) { // DC first
                // for each MCU, for each channel, ...
                for (int mcuHeightIndex = 0; mcuHeightIndex < mcuHeight; mcuHeightIndex++) {
                    for (int mcuWidthIndex = 0; mcuWidthIndex < mcuWidth; mcuWidthIndex++) {
                        // Restart marker:
                        if (!validateRestartMarker(bitStream, (mcuHeightIndex*mcuWidth)+mcuWidthIndex, restartInterval)) {
                            return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                        }
                        // For all channels:
                        for (int channelIndex = 0; channelIndex < imageComponentsInScan; channelIndex++) {
                            final int dcId = nth(ref(DC_AC_TABLE_SELECTOR), con(channelIndex)).evalSingle(scanState, Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact() >>> 4;
                            // If we are validating component #1 (luminance) then we might have multiple instances per MCU due to subsampling
                            for (int csFactorIndex = 0; csFactorIndex < (scanComponentSelectors[channelIndex] == componentIDs[0] ? (imageComponentsInScan > 1 ? luminanceCountPerMcu : calculateLuminanceCountInMcu(mcuWidthIndex, mcuHeightIndex, mcuWidth, mcuHeight, mcuWidthFactor, mcuHeightFactor, skipHorizontal, skipVertical, luminanceCountPerMcu)) : 1); csFactorIndex++) {
                                if (!validateDCFirstData(bitStream, validator.huffmanTables.get(DC).get(dcId), channelIndex)) {
                                    return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                                }
                            }
                        }
                        validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
                    }
                }
            } else { // AC first, 1 channel validation
                final int currentMcuCount = scanComponentSelectors[0] == componentIDs[0] ? mcuBaseCount : (mcuWidth * mcuHeight);
                final int acId = last(ref(DC_AC_TABLE_SELECTOR)).evalSingle(scanState, Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact() & 0x0F;
                for (int mcuIndex = 0; mcuIndex < currentMcuCount; mcuIndex++) {
                    // Restart marker:
                    if (!validateRestartMarker(bitStream, mcuIndex, restartInterval)) {
                        return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                    }
                    // for each MCU, for a single channel:
                    // validate from sOS to eOS
                    if (endOfBandSkips > 0) {
                        endOfBandSkips--;
                    } else {
                        if (!validateACFirstData(bitStream, validator.huffmanTables.get(AC).get(acId), startOfSpectralSelection, endOfSpectralSelection, myRefinableCoeffs[getComponentIndex(componentIDs, scanComponentSelectors[0])][mcuIndex])) {
                            return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                        }
                    }
                    validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
                }
            } // end AC first
        } else { // DC or AC refine
            if (startOfSpectralSelection == 0) { // DC refine
                for (int mcuHeightIndex = 0; mcuHeightIndex < mcuHeight; mcuHeightIndex++) {
                    for (int mcuWidthIndex = 0; mcuWidthIndex < mcuWidth; mcuWidthIndex++) {
                        // Restart marker:
                        if (!validateRestartMarker(bitStream, (mcuHeightIndex * mcuWidth) + mcuWidthIndex, restartInterval)) {
                            return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                        }
                        // In case of subsampling, there may be multiple luminance bits to skip per MCU
                        final boolean skipPossible = bitStream.skip(
                            scanComponentSelectors[0] != componentIDs[0] ? // Does this scan contain luminance?
                                imageComponentsInScan : // No: Skip 1 bit for each channel it does contain
                                (imageComponentsInScan == 1 ? // Yes: Is this a luminance-only scan?
                                    // Yes: Calculate whether to skip 1, 2 or 4 bits
                                    calculateLuminanceCountInMcu(mcuWidthIndex, mcuHeightIndex, mcuWidth, mcuHeight, mcuWidthFactor, mcuHeightFactor, skipHorizontal, skipVertical, luminanceCountPerMcu) :
                                    luminanceCountPerMcu + (imageComponentsInScan - 1)) // No: Skip max amount of luminance + 1 bit per chrominance channel
                        );
                        if (!skipPossible) {
                            info = "EOF";
                            return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                        }
                        validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
                    }
                }
            } else { // AC refine, 1 channel validation
                final int currentMcuCount = scanComponentSelectors[0] == componentIDs[0] ? mcuBaseCount : (mcuWidth * mcuHeight);
                final int acId = last(ref(DC_AC_TABLE_SELECTOR)).evalSingle(scanState, Encoding.DEFAULT_ENCODING).get().asNumeric().intValueExact() & 0x0F;
                for (int mcuIndex = 0; mcuIndex < currentMcuCount; mcuIndex++) {
                    // Restart marker:
                    if (!validateRestartMarker(bitStream, mcuIndex, restartInterval)) {
                        return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                    }
                    if (!validateACRefineData(bitStream, validator.huffmanTables.get(AC).get(acId), startOfSpectralSelection, endOfSpectralSelection, myRefinableCoeffs[getComponentIndex(componentIDs, scanComponentSelectors[0])][mcuIndex])) {
                        return new JpegValidationResult(false, BigInteger.valueOf(bitStream.getOffset()), validator, info);
                    }
                    validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
                }
            }
        }
        validator.reportedOffset = BigInteger.valueOf(bitStream.getOffset()).add(bitStream.getBitOffset() > 0 ? ONE : ZERO);
        return new JpegValidationResult(true, validator.reportedOffset, validator, info);
    }

    private int calculateLuminanceCountInMcu(final int mcuWidthIndex, final int mcuHeightIndex, final int mcuWidth, final int mcuHeight, final int mcuWidthFactor, final int mcuHeightFactor, final boolean skipHorizontal, final boolean skipVertical, final int luminanceCountPerMcu) {
        if (skipHorizontal && (mcuWidthIndex + 1 == mcuWidth)) {
            if (skipVertical && (mcuHeightIndex + 1 == mcuHeight)) {
                return luminanceCountPerMcu / (mcuWidthFactor * mcuHeightFactor);
            }
            return luminanceCountPerMcu / mcuWidthFactor;
        } else if (skipVertical && (mcuHeightIndex + 1 == mcuHeight)) {
            return luminanceCountPerMcu / mcuHeightFactor;
        }
        return luminanceCountPerMcu;
    }

    private boolean validateRestartMarker(JpegEntropyCodedBitStream input, int mcuIndex, int restartInterval) throws IOException {
        if (mcuIndex > 0 && restartInterval > 0 && (mcuIndex % restartInterval) == 0) { // If restartInterval == 0, there are no markers.
            if (input.getBitOffset() > 0) { // Align to next byte boundary.
                input.skip(8 - input.getBitOffset());
            }
            final Optional<BitSet> restartMarkerValue = input.peek(16);
            if (restartMarkerValue.isEmpty()) {
                info = "RestartM";
                return false;
            }
            final byte[] restartMarkerBytes = restartMarkerValue.get().toByteArray();
            // Integer.reverse() with >> 24 and & 0xff is used because BitSet provides the bytes in reversed order.
            if (restartMarkerBytes[0] != -1 || (byte)((Integer.reverse(restartMarkerBytes[1]) >> 24) & 0xff) != (byte)(208 + (((mcuIndex / restartInterval) - 1) % 8) & 0xff)) {
                info = "RestartM";
                return false;
            }
            input.skip(16);
        }
        return true;
    }

    private boolean validateDCFirstData(final JpegEntropyCodedBitStream input, final HuffmanTable dcTable, final int channelIndex) throws IOException {
        final Optional<BitSet> maxDCCodeLengthData = input.peek(dcTable.maxCodeLength);
        if (maxDCCodeLengthData.isEmpty()) {
            info = "EOF";
            return false;
        }
        final Optional<MatchResult> matchDCResult = dcTable.findShortestMatch(maxDCCodeLengthData.get());
        if (matchDCResult.isEmpty()) {
            info = "Huffman-DC-F; " + channelNames[channelIndex];
            return false; // No Huffmancode match found: this is a Huffmantable lookup error.
        }
        input.skip(matchDCResult.get().bitsMatched + matchDCResult.get().symbol);
        return true;
    }

    private boolean validateACFirstData(final JpegEntropyCodedBitStream input, final HuffmanTable acTable, final int startOfSpectralSelection, final int endOfSpectralSelection, final boolean[] refinableCoeffs) throws IOException {
        for (int quantizationArrayIndex = startOfSpectralSelection; quantizationArrayIndex <= endOfSpectralSelection; quantizationArrayIndex++) {
            final Optional<BitSet> maxACCodeLengthData = input.peek(acTable.maxCodeLength);
            if (maxACCodeLengthData.isEmpty()) {
                info = "EOF";
                return false;
            }
            final Optional<MatchResult> matchACResult = acTable.findShortestMatch(maxACCodeLengthData.get());
            if (matchACResult.isEmpty()) {
                info = "Huffman-AC-F";
                return false; // No Huffmancode match found: this is a Huffmantable lookup error.
            }
            final int numZeroes = matchACResult.get().symbol >>> 4;
            final int coeffLength = matchACResult.get().symbol & 0x000F;
            input.skip(matchACResult.get().bitsMatched);
            if (coeffLength != 0) {
                // numZeroes is the amount of zeroes to fill, the coeffLength indicates one value to read
                quantizationArrayIndex += numZeroes;
            } else if (numZeroes == 15) {
                quantizationArrayIndex += 15;
            } else if (numZeroes == 0) {
                return true;
            } else {
                // handle end-of-bands marker for numZeroes > 0 && < 15
                endOfBandSkips = 1 << numZeroes; // 2 ^ numZeroes
                endOfBandSkips += toInt(input.peek(numZeroes).get(), numZeroes); // add value of next numZeroes bits from input
                endOfBandSkips--; // skip current quantization array slice (deduct 1 from skips)

                input.skip(numZeroes);
                return true;
            }
            final Optional<BitSet> coeff = input.peek(coeffLength);
            if (coeff.isEmpty()) {
                info = "EOF";
                return false;
            }
            if (quantizationArrayIndex > endOfSpectralSelection) {
                info = "QASize";
                return false; // Quantization Array Size overflow found.
            }
            if (coeffLength > 0) {
                refinableCoeffs[quantizationArrayIndex] = true;
                input.skip(coeffLength);
            }
        }
        return true;
    }

    private boolean validateACRefineData(final JpegEntropyCodedBitStream input, final HuffmanTable acTable, final int startOfSpectralSelection, final int endOfSpectralSelection, final boolean[] refinableCoeffs) throws IOException {
        int ssIndex = startOfSpectralSelection;
        if (endOfBandSkips == 0) {
            for (; ssIndex <= endOfSpectralSelection; ssIndex++) {
                final Optional<BitSet> maxACCodeLengthData = input.peek(acTable.maxCodeLength);
                if (maxACCodeLengthData.isEmpty()) {
                    info = "EOF";
                    return false;
                }
                final Optional<MatchResult> matchACResult = acTable.findShortestMatch(maxACCodeLengthData.get());
                if (matchACResult.isEmpty()) {
                    info = "Huffman-AC-R";
                    return false; // No Huffmancode match found: this is a Huffmantable lookup error.
                }
                final int numZeroes = matchACResult.get().symbol >>> 4;
                final int coeffLength = matchACResult.get().symbol & 0x000F;
                input.skip(matchACResult.get().bitsMatched);

                // validate: coeffLength *must* be 0 or 1
                if (coeffLength != 0) { // So this is not an end-of-block/band command
                    if (coeffLength != 1) {
                        info = "Coeff-AC-R";
                        return false; // Since this is a refine, the size of the coeff must be 1
                    }
                    // coeffLength = 1 => read 1 bit
                    final Optional<BitSet> refineBit = input.peek(1);
                    if (refineBit.isEmpty()) {
                        info = "EOF";
                        return false;
                    }
                    input.skip(1);
                    for (int zeroesCount = 0; zeroesCount < numZeroes || refinableCoeffs[ssIndex];) {
                        if (refinableCoeffs[ssIndex]) {
                            input.skip(1);
                        } else {
                            zeroesCount++;
                        }
                        ssIndex++;
                        if (ssIndex > endOfSpectralSelection) {
                            info = "QASize";
                            return false;
                        }
                    }
                    refinableCoeffs[ssIndex] = true;
                } else {
                    // coeffLength = 0, numZeroes != 15 => new end-of-bands run
                    if (numZeroes != 15) {
                        // handle end-of-bands marker for numZeroes > 0 && < 15
                        endOfBandSkips = 1 << numZeroes; // 2 ^ numZeroes
                        endOfBandSkips += toInt(input.peek(numZeroes).get(), numZeroes); // add value of next numZeroes bits from input
                        input.skip(numZeroes);
                        break;
                    }
                    // coeffLength = 0, numZeroes = 15 => skip 16 zeroes
                    for (int zeroesCount = 0; zeroesCount < numZeroes || refinableCoeffs[ssIndex];) {
                        if (refinableCoeffs[ssIndex]) {
                            input.skip(1);
                        } else {
                            zeroesCount++;
                        }
                        ssIndex++;
                        if (ssIndex > endOfSpectralSelection) {
                            info = "QASize";
                            return false;
                        }
                    }
                }
            }
        }
        if (endOfBandSkips > 0) {
            for (; ssIndex <= endOfSpectralSelection; ssIndex++) {
                if (refinableCoeffs[ssIndex]) {
                    input.skip(1);
                }
            }
            endOfBandSkips--; // skip current quantization array slice (deduct 1 from skips)
        }
        return true;
    }

    public static int toInt(final BitSet bitSet, final int eobOrder) {
        int intValue = 0;
        for (int bit = 0; bit < bitSet.length(); bit++) {
            if (bitSet.get(bitSet.length() - bit - 1)) {
                intValue |= (1 << bit);
            }
        }
        intValue = intValue << (eobOrder - bitSet.length());
        return intValue;
    }

}
