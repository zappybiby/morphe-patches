Test build for [MorpheApp/morphe-patches#4680](https://github.com/MorpheApp/morphe-patches/issues/4680): YouTube Music can leave one Android Auto connection with empty tabs while another connection loads successfully. This patch reloads the empty connection after tabs become available.

The download contains one selectable patch: **Android Auto (empty root test)**.

To test:

1. Download `android-auto-4680-test-1.mpp`. In Morphe Manager, use **Sources → + → Local** to import it, then enable **Expert mode** to use it alongside the official source. Desktop also supports multiple patch sources.
2. Start with an original YouTube Music APK. **9.30.53** is recommended.
3. Keep your usual official Morphe patch selections, including **GmsCore support** for a non-root install. Deselect the official **Android Auto** and **Playlist tags** patches. Select **Android Auto (empty root test)** from this download.
4. Patch, install, then reconnect to Android Auto and check whether Home and Library load after a cold start.

Use alongside official Morphe Patches **2.2.0** or **2.3.0-dev.1**, which provide the shared extension and settings resources.

**Playlist tags** must be disabled for this add-on: it shares a fingerprint with Android Auto that cannot be reused across separate patch sources.

[Manager instructions for multiple sources](https://github.com/MorpheApp/morphe-manager/blob/main/docs/patch-sources.md#using-more-than-one-source).

Fix source: [712ea4ffd](https://github.com/zappybiby/morphe-patches/commit/712ea4ffd4a7c4e55dbc1411bb8f7b022b8ae5bf). The release branch adds packaging only; the fix branch remains separate.
