package com.schwab.shortener.url.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Base62Test {

    @Test
    @DisplayName("encoding then decoding gives back the original number")
    void encodeThenDecodeGivesBackTheOriginal() {
        long[] values = {0, 1, 61, 62, 3843, 1_000_000_000L, Base62.DOMAIN - 1};
        for (long value : values) {
            assertEquals(value, Base62.decode(Base62.encode(value)));
        }
    }

    @Test
    @DisplayName("every code is exactly six characters")
    void everyCodeIsSixCharacters() {
        assertEquals(6, Base62.encode(0).length());
        assertEquals(6, Base62.encode(1_000_000_000L).length());
        assertEquals(6, Base62.encode(Base62.DOMAIN - 1).length());
    }

    @Test
    @DisplayName("a billion encodes to the value quoted in the design")
    void aBillionEncodesAsExpected() {
        assertEquals("15ftgG", Base62.encode(1_000_000_000L));
    }

    @Test
    @DisplayName("values outside the code space are rejected rather than wrapped")
    void rejectsValuesOutsideTheCodeSpace() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(Base62.DOMAIN));
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1));
    }

    @Test
    @DisplayName("badly shaped codes are recognised without touching the database")
    void recognisesBadlyShapedCodes() {
        assertTrue(Base62.isValidCode("15ftgG"));
        assertFalse(Base62.isValidCode("abc"));
        assertFalse(Base62.isValidCode("15ft-G"));
        assertFalse(Base62.isValidCode(null));
    }
}
