package edu.kinneret.parking.app;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import edu.kinneret.parking.common.ui.LoginController;

/**
 * Unit tests for the Mulligan Unified Application gateway.
 */
public class MulliganAppTest {

    /**
     * Verifies that the default accounts, passwords, roles, and VIN associations
     * are correctly initialized in the static maps of the LoginController.
     */
    @Test
    public void testStaticCredentialsLoaded() {
        assertNotNull(LoginController.signupPasswords, "Passwords map must not be null");
        assertNotNull(LoginController.signupRoles, "Roles map must not be null");
        assertNotNull(LoginController.signupVins, "VINs map must not be null");

        assertEquals("customer_secure_pass_2026", LoginController.signupPasswords.get("customer"));
        assertEquals("Customer", LoginController.signupRoles.get("customer"));
        assertEquals("604-95-839", LoginController.signupVins.get("customer"));

        assertEquals("peo_secure_pass_2026", LoginController.signupPasswords.get("peo_service"));
        assertEquals("PEO", LoginController.signupRoles.get("peo_service"));

        assertEquals("admin_ultra_secure_99", LoginController.signupPasswords.get("mulligan_admin"));
        assertEquals("MO", LoginController.signupRoles.get("mulligan_admin"));
    }
}
