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

import static io.parsingdata.metal.Shorthand.SELF;
import static io.parsingdata.metal.Shorthand.add;
import static io.parsingdata.metal.Shorthand.and;
import static io.parsingdata.metal.Shorthand.cho;
import static io.parsingdata.metal.Shorthand.con;
import static io.parsingdata.metal.Shorthand.def;
import static io.parsingdata.metal.Shorthand.elvis;
import static io.parsingdata.metal.Shorthand.eq;
import static io.parsingdata.metal.Shorthand.eqNum;
import static io.parsingdata.metal.Shorthand.fold;
import static io.parsingdata.metal.Shorthand.gtNum;
import static io.parsingdata.metal.Shorthand.last;
import static io.parsingdata.metal.Shorthand.ltNum;
import static io.parsingdata.metal.Shorthand.mul;
import static io.parsingdata.metal.Shorthand.not;
import static io.parsingdata.metal.Shorthand.offset;
import static io.parsingdata.metal.Shorthand.or;
import static io.parsingdata.metal.Shorthand.ref;
import static io.parsingdata.metal.Shorthand.rep;
import static io.parsingdata.metal.Shorthand.repn;
import static io.parsingdata.metal.Shorthand.scope;
import static io.parsingdata.metal.Shorthand.seq;
import static io.parsingdata.metal.Shorthand.sub;
import static io.parsingdata.metal.Shorthand.whl;

import io.parsingdata.metal.Shorthand;
import io.parsingdata.metal.token.Token;

public final class JpegStructure {

    public static final String SOF = "SOF";
    public static final String MARKER = "marker";
    public static final String IDENTIFIER = "identifier";
    public static final String LENGTH = "length";
    public static final String PAYLOAD = "payload";
    public static final String NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME = "number_of_image_components_in_frame";
    public static final String NUMBER_OF_IMAGE_COMPONENTS_IN_SCAN = "number_of_image_components_in_scan";
    public static final String TABLE_CLASS_TABLE_HUFFMAN_IDENTIFIER = "table_class_table_huffman_identifier";
    public static final String HEIGHT = "height";
    public static final String WIDTH = "width";
    public static final String RESTART_INTERVAL = "restart_interval";
    public static final int SOF0_IDENTIFIER = 0xc0;
    public static final int SOF2_IDENTIFIER = 0xc2;
    public static final String START_OF_SPECTRAL_SELECTION = "start_of_spectral_or_predictor_selection";
    public static final String END_OF_SPECTRAL_SELECTION = "end_of_spectral_selection";
    public static final String SCAN_COMPONENT_SELECTOR = "scan_component_selector";
    public static final String DC_AC_TABLE_SELECTOR = "dc_ac_table_selector";
    public static final String HIGH_AND_LOW_SUCCESSIVE_APPROXIMATION_BIT_POSITION = "high_and_low_successive_approximation_bit_position";

    private JpegStructure() {}

    static final Token SOI =
        seq("SOI", // Start of Image
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xd8)))); // SOI

    static final Token SIZED_SEGMENT =
        seq("SizedSegment",
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), and(or(ltNum(con(0xd0)), gtNum(con(0xda))), and(not(eq(con(0xc0))), not(eq(con(0xc4)))))),
            def(LENGTH, con(2)),
            def(PAYLOAD, sub(last(ref(LENGTH)), con(2))));

    static final Token SOFX =
        seq(SOF, // Start of Frame
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), or(eq(con(SOF0_IDENTIFIER)), eq(con(SOF2_IDENTIFIER)))), // We recognize SOF0 or SOF2 here
            def(LENGTH, con(2)), // Lf
            def("precision", con(1)), // P
            def(HEIGHT, con(2)), // Y
            def(WIDTH, con(2)), // X
            def(NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME, con(1), eqNum(add(mul(SELF, con(3)), con(8)), last(ref(LENGTH)))), // Nf
            repn(
                seq(
                    def("component_identifier", con(1)), // Ci
                    def("sampling_factors", con(1)), // Hi & Vi
                    def("quantization_table_destination", con(1)) // Tqi
                ),
                last(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_FRAME))
            )
        );

    static final Token HT =
        seq("single_huffman_table_definition",
            def(TABLE_CLASS_TABLE_HUFFMAN_IDENTIFIER, con(1)), // Tc & Th
            repn(
                def("li", con(1)), // Li
                con(16)
            ),
            repn(
                def("vij", con(1)), // Vij
                fold(scope(ref("li"), con(0)), Shorthand::add)
            )
        );

    static final Token DHT =
        seq("DHT", // Define Huffman Table
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xc4))), // DHT
            def(LENGTH, con(2)), // Lh
            whl(HT,
                gtNum(
                    add(offset(last(ref(LENGTH))), last(ref(LENGTH))),
                    add(elvis(offset(last(ref("vij"))), con(0)), con(1))
                )
            )
        );

    static final Token DRI =
        seq("DRI",
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xdd))), // DRI
            def(LENGTH, con(2), eqNum(con(4))), // Ls
            def(RESTART_INTERVAL, con(2))
        );

    static final Token SOS =
        seq("SOS", // Start of Scan
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xda))), // SOS
            def(LENGTH, con(2)), // Ls
            def(NUMBER_OF_IMAGE_COMPONENTS_IN_SCAN, con(1), eqNum(add(mul(SELF, con(2)), con(6)), last(ref(LENGTH)))), // Ns
            repn(
                seq(
                    def(SCAN_COMPONENT_SELECTOR, con(1)), // Csj
                    def(DC_AC_TABLE_SELECTOR, con(1)) // Tdj & Taj
                ),
                last(ref(NUMBER_OF_IMAGE_COMPONENTS_IN_SCAN))
            ),
            def(START_OF_SPECTRAL_SELECTION, con(1)), // Ss
            def(END_OF_SPECTRAL_SELECTION, con(1)), // Se
            def(HIGH_AND_LOW_SUCCESSIVE_APPROXIMATION_BIT_POSITION, con(1)) // Ah & Al
        );

    static final Token EOI =
        seq("EOI", // End of Image
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xd9)))); // EOI

    static final Token COMMENT =
        seq("SizedSegment",
            def(MARKER, con(1), eq(con(0xff))),
            def(IDENTIFIER, con(1), eq(con(0xfe))),
            def(LENGTH, con(2)),
            def(PAYLOAD, sub(last(ref(LENGTH)), con(2))));

    static final Token FF00 =
        seq("FF00",
            def("FF", con(1), eq(con(0xff))),
            def("00", con(1), eq(con(0)))
            );

    public static final Token HEADER =
        seq("JpegHeader",
            SOI,
            rep(cho(DHT, SOFX, DRI, SIZED_SEGMENT)));

    public static final Token SCAN =
        seq("JpegScan",
            rep(cho(DHT, DRI, SIZED_SEGMENT)),
            SOS
            );

    public static final Token FOOTER =
        seq("Footer",
            rep(cho(COMMENT, FF00)),
            EOI);

}
