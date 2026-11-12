package us.leaf3stones.hy2droid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The heuristics behind "why is it not working". */
class CoreDiagnosisTest {

    @Test
    fun `a name that cannot be resolved is reported`() {
        assertEquals(
            CoreProblem.HostNotFound,
            CoreDiagnosis.classify("ERROR dial tcp: lookup example.com: no such host"),
        )
    }

    @Test
    fun `certificate failures are reported`() {
        assertEquals(
            CoreProblem.Tls,
            CoreDiagnosis.classify(
                "failed to dial: tls: failed to verify certificate: x509: certificate signed by unknown authority",
            ),
        )
    }

    @Test
    fun `a rejected password is reported`() {
        assertEquals(
            CoreProblem.Auth,
            CoreDiagnosis.classify("authentication failed: auth error: rejected"),
        )
    }

    @Test
    fun `a silent server shows up as a timeout`() {
        assertEquals(
            CoreProblem.Timeout,
            CoreDiagnosis.classify("quic dial: timeout: no recent network activity"),
        )
        assertEquals(
            CoreProblem.Timeout,
            CoreDiagnosis.classify("dial tcp 1.2.3.4:443: i/o timeout"),
        )
    }

    @Test
    fun `a refused port is reported`() {
        assertEquals(
            CoreProblem.Refused,
            CoreDiagnosis.classify("dial tcp 1.2.3.4:443: connect: connection refused"),
        )
    }

    @Test
    fun `an obfs mismatch is reported even though it reads like a password error`() {
        assertEquals(
            CoreProblem.Obfs,
            CoreDiagnosis.classify("failed to read response: salamander: invalid password"),
        )
    }

    @Test
    fun `routine lines are not a diagnosis`() {
        assertNull(CoreDiagnosis.classify("INFO connected to server"))
        assertNull(CoreDiagnosis.classify(""))
        assertNull(CoreDiagnosis.classify("client: [redacted: line contains credentials]"))
    }
}
