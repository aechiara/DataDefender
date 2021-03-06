/*
 *
 * Copyright 2014, Armenak Grigoryan, and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 */



package com.strider.datadefender.functions;

import org.junit.Test;

import com.strider.datadefender.extensions.BiographicFunctions;

import junit.framework.TestCase;

/**
 * Biographic data anonymizer functions
 *
 * @author Matthew Eaton
 */
public class BiographicFunctionsTest extends TestCase {
    public BiographicFunctionsTest(final String testName) {
        super(testName);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    @Test
    public void testSINValidation() {

        // Fake, but valid SINs
        final String validSinList[] = { "503247512", "943209502", "514022037", "455717686", "372184101" };

        for (int i = 0; i < validSinList.length; i++) {
            assertTrue(BiographicFunctions.isValidSIN(validSinList[i]));
        }

        final String invalidSinList[] = { "012345678", "123", "abcdefgff", "405717686", "372114101" };

        for (int i = 0; i < invalidSinList.length; i++) {
            assertFalse(BiographicFunctions.isValidSIN(invalidSinList[i]));
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
    }
}


//~ Formatted by Jindent --- http://www.jindent.com
