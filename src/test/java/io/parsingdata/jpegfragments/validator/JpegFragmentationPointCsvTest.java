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

package io.parsingdata.jpegfragments.validator;

import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import io.parsingdata.jpegfragments.validator.jpeg.JpegValidationResult;
import io.parsingdata.jpegfragments.validator.jpeg.JpegValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class JpegFragmentationPointCsvTest {

    public static final int RANDOM_TEST_CASES = 100;
    public static final int BLOCK_SIZE = 4096;
    public static final int PREFIX_SIZE = 1 * BLOCK_SIZE; // point after which the fragmentation point is created.
    public static final int RANDOM_SIZE = 8 * BLOCK_SIZE; // amount of data in which the validator can find an error.
    public static final List<byte[]> randomSuffixes = new ArrayList<>();
    public static final List<byte[]> filePrefixes = new ArrayList<>();
    public static final List<String> fileNames = new ArrayList<>();

    public static final String[] CSV_PATH_PREFIXES = {
            "/jpeg_research_ht_maximum",
            "/jpeg_research_ht_minimum",
            "/jpeg_research_rst_minimum",
            "/jpeg_research_rst_uniform",
            "/jpeg_research_sos_minimum",
            "/jpeg_baseline_first1000",
            "/jpeg_baseline_grayscale",
            "/jpeg_baseline_subsampling",
            "/jpeg_baseline_restartmarkers",
            "/jpeg_progressive_spectral_vanilla",
            "/jpeg_progressive_spectral_grayscale",
            "/jpeg_progressive_spectral_subsampling",
            "/jpeg_progressive_successive_vanilla",
            "/jpeg_progressive_successive_grayscale",
            "/jpeg_progressive_successive_subsampling",
            "/jpeg_research_baseline_sof0",
            "/jpeg_research_baseline_sof2"
    };

    @BeforeAll
    public static void beforeAll() throws URISyntaxException, IOException {
        for (long i = 0; i < RANDOM_TEST_CASES; i++) {
            final Random rng = new Random(i);
            final byte[] randomCluster = new byte[RANDOM_SIZE];
            rng.nextBytes(randomCluster);
            randomSuffixes.add(randomCluster);
        }
        for (String pathPrefix : CSV_PATH_PREFIXES) {
            final List<String> paths = Files.readAllLines(Paths.get(JpegFragmentationPointCsvTest.class.getResource(pathPrefix + ".csv").toURI()));
            for (String path : paths) {
                final byte[] fileContents = Files.readAllBytes(Paths.get(JpegFragmentationPointCsvTest.class.getResource(pathPrefix + "/" + path).toURI()));
                final byte[] filePrefix = new byte[PREFIX_SIZE];
                System.arraycopy(fileContents, 0, filePrefix, 0, PREFIX_SIZE);
                filePrefixes.add(filePrefix);
                fileNames.add(path);
            }
        }
    }

    private static Stream<Arguments> generateValidateCases() {
        final List<Arguments> argumentList = new ArrayList<>();
        for (int i = 0; i < filePrefixes.size(); i++) {
            for (int j = 0; j < randomSuffixes.size(); j++) {
                argumentList.add(arguments(fileNames.get(i), j, filePrefixes.get(i), randomSuffixes.get(j)));
            }
        }
        return argumentList.stream();
    }

    @ParameterizedTest(name = "file: {0}, rng: {1}")
    @MethodSource("generateValidateCases")
    public void detectFragmentationPointCsv(final String path, final int randomIndex, final byte[] prefix, final byte[] suffix) throws URISyntaxException, IOException {
        final byte[] testData = new byte[PREFIX_SIZE + RANDOM_SIZE];
        System.arraycopy(prefix, 0, testData, 0, PREFIX_SIZE);
        System.arraycopy(suffix, 0, testData, PREFIX_SIZE, RANDOM_SIZE);
        final JpegValidationResult result = new JpegValidator().validate(new InMemoryByteStream(testData));
        final boolean fpDetectionResult = result.offset.compareTo(BigInteger.valueOf(PREFIX_SIZE - 1)) >= 0 && result.offset.compareTo(BigInteger.valueOf(PREFIX_SIZE + RANDOM_SIZE)) < 0;
        System.out.println(String.format("%-20s", path.substring(0, Math.min(path.length(), 20))) + "; " + String.format("%4d", randomIndex) + "; " + resultToString(result.completed, fpDetectionResult) + "; " + String.format("%8d", result.offset.intValueExact()) + "; " + String.format("%8d", result.offset.intValueExact() - PREFIX_SIZE) + "; " + result.info);
    }

    private String resultToString(final boolean completed, final boolean result) {
        return !completed && result ? "PASSED" : "FAILED";
    }

}
