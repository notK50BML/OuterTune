/*
 * Copyright (C) 2026 OuterTune Project
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.desktop

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.LongByReference
import com.sun.jna.ptr.PointerByReference
import java.awt.EventQueue
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Windows' own media controls (System Media Transport Controls), for the desktop app.
 *
 * What every Windows music app plugs into: the media keys work when the window is not focused, and
 * the song - title, artist, cover - with play/pause, next and previous appears in the volume flyout,
 * the quick-settings media panel and on the lock screen.
 *
 * Reached through JNA and the raw WinRT ABI rather than a binding library: three calls into
 * combase.dll to get the controls for this window, then method calls by vtable slot. The interface
 * ids and slot orders are Windows SDK definitions (windows.media.idl and friends); the one
 * parameterised id, the ButtonPressed handler's, is derived with WinRT's documented algorithm and
 * matches the projected value 0557e996-7b23-5bae-aa81-ea0d671143a4.
 *
 * Everything runs on one dedicated thread, initialised for the multithreaded apartment, so no COM
 * call is ever made from the UI thread. If any step fails - an old Windows, or not Windows at all -
 * this stays off and the in-window media keys carry on as before.
 */
object WindowsMediaControls {

    enum class Button { Play, Pause, Next, Previous, Stop }

    /** True once Windows has accepted us; the window's own media-key handling stands down then. */
    @Volatile
    var active = false
        private set

    /** Called on the UI thread when a media key or a flyout button is pressed. */
    @Volatile
    var onButton: ((Button) -> Unit)? = null

    private val thread = Executors.newSingleThreadExecutor { r -> Thread(r, "media-controls").apply { isDaemon = true } }
    private var smtc: Pointer? = null
    private var updater: Pointer? = null
    private var music: Pointer? = null
    private var streamStatics: Pointer? = null
    private var uriFactory: Pointer? = null

    // The handler object Windows calls back into, kept reachable for the life of the app.
    private var handler: Memory? = null
    private val callbacks = mutableListOf<Callback>()

    private interface Combase : Library {
        fun RoInitialize(initType: Int): Int
        fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun RoGetActivationFactory(classId: Pointer, iid: Pointer, factory: PointerByReference): Int
    }

    private val combase by lazy { Native.load("combase", Combase::class.java) }

    /** Connects this window to the media controls. Safe to call once the window is showing. */
    fun start(window: java.awt.Window) {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return
        thread.execute {
            runCatching { connect(hwnd) }.onFailure { active = false }
        }
    }

    private fun connect(hwnd: Pointer) {
        combase.RoInitialize(RO_INIT_MULTITHREADED)
        val interop = factory("Windows.Media.SystemMediaTransportControls", IID_INTEROP)
        val controls = PointerByReference()
        // ISystemMediaTransportControlsInterop::GetForWindow, the one method after IInspectable's.
        check(call(interop, 6, hwnd, guid(IID_SMTC), controls) == 0)
        val s = controls.value
        smtc = s

        call(s, SMTC_PUT_IS_ENABLED, 1)
        call(s, SMTC_PUT_IS_PLAY_ENABLED, 1)
        call(s, SMTC_PUT_IS_PAUSE_ENABLED, 1)
        call(s, SMTC_PUT_IS_NEXT_ENABLED, 1)
        call(s, SMTC_PUT_IS_PREVIOUS_ENABLED, 1)
        call(s, SMTC_PUT_IS_STOP_ENABLED, 1)

        val display = PointerByReference()
        check(call(s, SMTC_GET_DISPLAY_UPDATER, display) == 0)
        updater = display.value
        call(display.value, UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)
        val props = PointerByReference()
        check(call(display.value, UPDATER_GET_MUSIC_PROPERTIES, props) == 0)
        music = props.value

        runCatching { streamStatics = factory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REF_STATICS) }
        runCatching { uriFactory = factory("Windows.Foundation.Uri", IID_URI_FACTORY) }

        val token = LongByReference()
        check(call(s, SMTC_ADD_BUTTON_PRESSED, buildHandler(), token) == 0)
        active = true
    }

    /**
     * What Windows shows. [playing] null means nothing is loaded. [artworkUrl] is shown only for
     * http(s) covers - Windows fetches it itself; a local file's embedded art is not offered.
     */
    fun update(title: String?, artist: String?, album: String?, artworkUrl: String?, playing: Boolean?) {
        if (!active) return
        thread.execute {
            runCatching {
                val s = smtc ?: return@runCatching
                val u = updater ?: return@runCatching
                val m = music ?: return@runCatching
                call(s, SMTC_PUT_PLAYBACK_STATUS, when (playing) { null -> STATUS_STOPPED; true -> STATUS_PLAYING; false -> STATUS_PAUSED })
                if (title == null) {
                    call(u, UPDATER_CLEAR_ALL)
                    call(u, UPDATER_PUT_TYPE, MEDIA_TYPE_MUSIC)
                } else {
                    withHString(title) { call(m, MUSIC_PUT_TITLE, it) }
                    withHString(artist.orEmpty()) { call(m, MUSIC_PUT_ARTIST, it) }
                    withHString(album.orEmpty()) { call(m, MUSIC_PUT_ALBUM_ARTIST, it) }
                    thumbnail(artworkUrl)?.let {
                        call(u, UPDATER_PUT_THUMBNAIL, it)
                        call(it, 2) // Release: the updater keeps its own reference.
                    }
                }
                call(u, UPDATER_UPDATE)
            }
        }
    }

    private fun thumbnail(url: String?): Pointer? {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        val statics = streamStatics ?: return null
        val uris = uriFactory ?: return null
        val uri = PointerByReference()
        val ok = withHString(url) { call(uris, 6, it, uri) } == 0
        if (!ok) return null
        val ref = PointerByReference()
        // IRandomAccessStreamReferenceStatics::CreateFromUri, the second of its three methods.
        val made = call(statics, 7, uri.value, ref) == 0
        call(uri.value, 2) // Release: the reference holds what it needs.
        return if (made) ref.value else null
    }

    // ---- the ButtonPressed handler -----------------------------------------------------------

    private interface QueryInterface : Callback {
        fun invoke(self: Pointer, iid: Pointer, out: PointerByReference): Int
    }

    private interface RefCount : Callback {
        fun invoke(self: Pointer): Int
    }

    private interface Invoke : Callback {
        fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int
    }

    /**
     * A minimal COM object implementing TypedEventHandler<SystemMediaTransportControls,
     * ButtonPressedEventArgs>: QueryInterface, AddRef, Release, Invoke. It lives for the whole run,
     * so reference counting is a formality.
     */
    private fun buildHandler(): Pointer {
        val known = listOf(IID_UNKNOWN, IID_AGILE, IID_BUTTON_HANDLER).map { bytesOf(UUID.fromString(it)) }
        val qi = object : QueryInterface {
            override fun invoke(self: Pointer, iid: Pointer, out: PointerByReference): Int {
                val asked = iid.getByteArray(0, 16)
                return if (known.any { it.contentEquals(asked) }) {
                    out.value = self
                    0
                } else {
                    out.value = null
                    E_NOINTERFACE
                }
            }
        }
        val ref = object : RefCount {
            override fun invoke(self: Pointer): Int = 1
        }
        val fire = object : Invoke {
            override fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int {
                if (args == null) return 0
                val button = IntByReference()
                if (call(args, 6, button) != 0) return 0
                val which = when (button.value) {
                    0 -> Button.Play
                    1 -> Button.Pause
                    2 -> Button.Stop
                    6 -> Button.Next
                    7 -> Button.Previous
                    else -> return 0
                }
                EventQueue.invokeLater { onButton?.invoke(which) }
                return 0
            }
        }
        callbacks += listOf(qi, ref, fire)
        val vtable = Memory(4L * Native.POINTER_SIZE).apply {
            setPointer(0, com.sun.jna.CallbackReference.getFunctionPointer(qi))
            setPointer(1L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(ref))
            setPointer(2L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(ref))
            setPointer(3L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(fire))
        }
        val obj = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }
        handler = obj
        vtableKeepAlive = vtable
        return obj
    }

    private var vtableKeepAlive: Memory? = null

    // ---- plumbing -----------------------------------------------------------------------------

    private fun factory(className: String, iid: String): Pointer =
        withHString(className) { name ->
            val out = PointerByReference()
            check(combase.RoGetActivationFactory(name, guid(iid), out) == 0) { "no factory for $className" }
            out.value
        }

    private fun <T> withHString(text: String, block: (Pointer) -> T): T {
        val out = PointerByReference()
        check(combase.WindowsCreateString(WString(text), text.length, out) == 0)
        return try {
            block(out.value)
        } finally {
            combase.WindowsDeleteString(out.value)
        }
    }

    /** Calls method [slot] of the COM object [obj], passing it as `this`. Returns the HRESULT. */
    private fun call(obj: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = obj.getPointer(0)
        val fn = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE))
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun guid(text: String): Memory = Memory(16).apply { write(0, bytesOf(UUID.fromString(text)), 0, 16) }

    /** A GUID's in-memory layout: the first three fields little-endian, the last eight bytes as is. */
    private fun bytesOf(uuid: UUID): ByteArray {
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        val d1 = (msb ushr 32).toInt()
        val d2 = ((msb ushr 16) and 0xFFFF).toInt()
        val d3 = (msb and 0xFFFF).toInt()
        val out = ByteArray(16)
        out[0] = d1.toByte(); out[1] = (d1 ushr 8).toByte(); out[2] = (d1 ushr 16).toByte(); out[3] = (d1 ushr 24).toByte()
        out[4] = d2.toByte(); out[5] = (d2 ushr 8).toByte()
        out[6] = d3.toByte(); out[7] = (d3 ushr 8).toByte()
        for (i in 0 until 8) out[8 + i] = (lsb ushr (56 - 8 * i)).toByte()
        return out
    }

    private const val RO_INIT_MULTITHREADED = 1
    private const val E_NOINTERFACE = 0x80004002.toInt()

    private const val IID_UNKNOWN = "00000000-0000-0000-c000-000000000046"
    private const val IID_AGILE = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
    private const val IID_INTEROP = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
    private const val IID_SMTC = "99fa3ff4-1742-42a6-902e-087d41f965ec"
    private const val IID_BUTTON_HANDLER = "0557e996-7b23-5bae-aa81-ea0d671143a4"
    private const val IID_STREAM_REF_STATICS = "857309dc-3fbf-4e7d-986f-ef3b1a07a964"
    private const val IID_URI_FACTORY = "44a9796f-723e-4fdf-a218-033e75b0c084"

    // Slots: IUnknown (3) and IInspectable (3) come first, so an interface's own methods start at 6.
    private const val SMTC_PUT_PLAYBACK_STATUS = 7
    private const val SMTC_GET_DISPLAY_UPDATER = 8
    private const val SMTC_PUT_IS_ENABLED = 11
    private const val SMTC_PUT_IS_PLAY_ENABLED = 13
    private const val SMTC_PUT_IS_STOP_ENABLED = 15
    private const val SMTC_PUT_IS_PAUSE_ENABLED = 17
    private const val SMTC_PUT_IS_PREVIOUS_ENABLED = 25
    private const val SMTC_PUT_IS_NEXT_ENABLED = 27
    private const val SMTC_ADD_BUTTON_PRESSED = 32

    private const val UPDATER_PUT_TYPE = 7
    private const val UPDATER_PUT_THUMBNAIL = 11
    private const val UPDATER_GET_MUSIC_PROPERTIES = 12
    private const val UPDATER_CLEAR_ALL = 16
    private const val UPDATER_UPDATE = 17

    private const val MUSIC_PUT_TITLE = 7
    private const val MUSIC_PUT_ALBUM_ARTIST = 9
    private const val MUSIC_PUT_ARTIST = 11

    private const val MEDIA_TYPE_MUSIC = 1
    private const val STATUS_STOPPED = 2
    private const val STATUS_PLAYING = 3
    private const val STATUS_PAUSED = 4
}
