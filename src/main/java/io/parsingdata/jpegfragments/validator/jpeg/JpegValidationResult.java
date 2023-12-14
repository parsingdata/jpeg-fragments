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

import java.math.BigInteger;

import io.parsingdata.jpegfragments.ValidationResult;
import io.parsingdata.jpegfragments.Validator;

public class JpegValidationResult extends ValidationResult {

    public String info;

    private JpegValidationResult(final boolean completed, final BigInteger offset, final Validator validator) {
        super(completed, offset, validator);
    }

    public JpegValidationResult(final boolean completed, final BigInteger offset, final Validator validator, final String info) {
        this(completed, offset, validator);
        this.info = info;
    }

    @Override
    public String toString() {
        return super.toString() + " info: " + info;
    }
}
