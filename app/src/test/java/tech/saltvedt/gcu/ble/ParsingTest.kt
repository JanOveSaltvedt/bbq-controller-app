package tech.saltvedt.gcu.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.saltvedt.gcu.model.ControllerEventKind
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ParsingTest {

    private fun f32le(value: Float): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array()

    private fun u32le(value: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array()

    @Test
    fun `f32 decodes little-endian`() {
        assertEquals(1.5f, Parsing.f32(f32le(1.5f)), 0.0001f)
        assertEquals(-12.25f, Parsing.f32(f32le(-12.25f)), 0.0001f)
    }

    @Test
    fun `u32 decodes unsigned little-endian`() {
        assertEquals(0xFFFFFFFEL, Parsing.u32(u32le(0xFFFFFFFEL)))
        assertEquals(258L, Parsing.u32(byteArrayOf(0x02, 0x01, 0x00, 0x00)))
    }

    @Test
    fun `f32 stale sentinel returns null`() {
        val stale = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertNull(Parsing.parseF32OrStale(stale))
        assertEquals(24.0f, Parsing.parseF32OrStale(f32le(24.0f))!!, 0.0001f)
    }

    @Test
    fun `target position parses flag and value`() {
        val idle = Parsing.parseTarget(byteArrayOf(0x00, 0, 0, 0, 0))
        assertFalse(idle.first)
        assertNull(idle.second)

        val moving = Parsing.parseTarget(byteArrayOf(0x01) + f32le(3.25f))
        assertTrue(moving.first)
        assertEquals(3.25f, moving.second!!, 0.0001f)
    }

    @Test
    fun `motor status parses fields and all-zero is stale`() {
        assertNull(Parsing.parseMotorStatus(ByteArray(14)))

        val bytes = byteArrayOf(8, 1) + f32le(2.5f) + f32le(0.4f) + f32le(1.1f)
        val status = Parsing.parseMotorStatus(bytes)
        assertNotNull(status)
        assertEquals(8, status!!.axisState)
        assertTrue(status.isRunning)
        assertEquals(2.5f, status.busCurrent, 0.0001f)
        assertEquals(0.4f, status.torque, 0.0001f)
        assertEquals(1.1f, status.velocityGearbox, 0.0001f)
    }

    @Test
    fun `errors parse active and disarm`() {
        val (active, disarm) = Parsing.parseErrors(u32le(0x10L) + u32le(0x20L))
        assertEquals(0x10L, active)
        assertEquals(0x20L, disarm)
    }

    @Test
    fun `controller event parses kind and payload`() {
        val event = Parsing.parseEvent(byteArrayOf(0x02) + u32le(0x1234L))
        assertNotNull(event)
        assertEquals(ControllerEventKind.OdriveError, event!!.kind)
        assertEquals(0x1234L, event.payload)
    }

    @Test
    fun `flip_by command is kind 0 plus little-endian f32`() {
        val cmd = Parsing.flipByCommand(2.0f)
        assertEquals(5, cmd.size)
        assertEquals(0x00.toByte(), cmd[0])
        assertEquals(2.0f, Parsing.f32(cmd, 1), 0.0001f)
    }

    @Test
    fun `set_max_velocity command is kind 4 plus little-endian f32`() {
        val cmd = Parsing.setMaxVelocityCommand(0.1f)
        assertEquals(5, cmd.size)
        assertEquals(0x04.toByte(), cmd[0])
        assertEquals(0.1f, Parsing.f32(cmd, 1), 0.0001f)
    }

    @Test
    fun `temp decode matches legacy formula`() {
        // Build a frame where data[9]=0x64, data[8]=0x01 => raw=(0x64<<2)|1=401, f=301
        val data = ByteArray(12)
        data[2] = (0x80.toByte().toInt() or 55).toByte() // battery bit7 set + 55
        data[3] = 0x10                                   // probe id 1
        data[8] = 0x01
        data[9] = 0x64
        val reading = Parsing.decodeTemp(data)
        assertNotNull(reading)
        assertEquals(1, reading!!.probeId)
        assertEquals(55, reading.battery)
        val expectedC = ((301 - 32) * 5.0 / 9.0).toFloat()
        assertEquals(expectedC, reading.celsius, 0.01f)
    }

    @Test
    fun `temp decode rejects short frames`() {
        assertNull(Parsing.decodeTemp(ByteArray(5)))
    }
}
