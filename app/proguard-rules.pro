# Quire's R8 keep rules.
#
# Not applied unless the build asks for them: the release build type sets
# minifyEnabled from -PquireMinify, which defaults to false. See docs/release.md
# for why, and for the device test that has to pass before it is turned on.
#
# These are written and committed anyway, because the day someone enables
# shrinking is the wrong day to start researching what PdfBox needs. Every rule
# below names the failure it prevents — none of them is cargo cult.

# ---------------------------------------------------------------------------
# Room
# ---------------------------------------------------------------------------
# Room generates QuireDatabase_Impl and Room.databaseBuilder finds it by name,
# building the string from the database class. R8 sees no reference to the
# generated class and deletes it; the app then throws "cannot find implementation
# for app.quire.android.data.QuireDatabase" at first launch, after the splash.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class app.quire.android.data.**_Impl { *; }

# Entity fields are read back by the generated cursor code, which is kept, but the
# @Entity classes themselves are also constructed reflectively by Room's testing
# and migration paths.
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# kotlinx.serialization
# ---------------------------------------------------------------------------
# Every chapter of every imported book is on disk as JSON. The compiler plugin
# generates a `$$serializer` object per @Serializable class and the runtime finds
# it through the class's Companion — neither is referenced from Kotlin source, so
# both look dead to a shrinker. Losing them does not fail the build: it fails at
# the moment a reader opens a book they imported last week.
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** serializer(...);
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
# The model package is kept whole. It is small, it is the on-disk format of the
# reader's library, and a subtle shrinker mistake here is unreadable books rather
# than a crash anyone would notice in testing.
-keep class app.quire.core.model.** { *; }
-keep class app.quire.core.model.**$$serializer { *; }

# ---------------------------------------------------------------------------
# WorkManager
# ---------------------------------------------------------------------------
# ImportWorker is constructed by QuireWorkerFactory, but WorkManager's own
# fallback path and its internal diagnostics still resolve worker classes by the
# name stored in the database — including for work enqueued by a previous install
# and restored after reboot.
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.WorkerFactory { *; }

# ---------------------------------------------------------------------------
# PdfBox-Android
# ---------------------------------------------------------------------------
# The reason R8 is off by default. PdfBox loads font mappings, CMaps and codec
# implementations by class name from its own bundled assets, and none of those
# names appear in bytecode. A shrunk PdfBox does not crash: it returns an empty
# text layer, so a PDF imports "successfully" as a book with no words in it.
-keep class com.tom_roush.pdfbox.** { *; }
-keep class com.tom_roush.fontbox.** { *; }
-keep class com.tom_roush.harmony.** { *; }
-dontwarn com.tom_roush.**
# PdfBox-Android carries interfaces to desktop Java that Android does not have.
# They are never reached on device; without this R8 refuses to finish.
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-dontwarn javax.xml.**
-dontwarn org.apache.**

# ---------------------------------------------------------------------------
# Compose
# ---------------------------------------------------------------------------
# Compose ships its own consumer rules and needs almost nothing here. The one
# thing worth pinning is that the runtime reads @Composable off methods when it
# builds a composition trace; stripping the annotation turns a useful stack into
# an anonymous one, which is how a shrunk-only crash becomes unreproducible.
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------------
# Crash reports
# ---------------------------------------------------------------------------
# Quire sends no crash reports anywhere — it has no INTERNET permission. These
# are for the line numbers in a stack trace a reader pastes into an email, which
# is the only crash report this app will ever receive.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
