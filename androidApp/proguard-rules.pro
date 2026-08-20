# Room, kotlinx.serialization, Coil and Compose all ship their own consumer rules; what is left is the
# reflection this app does itself.

# kotlinx.serialization looks a generated serializer up by name, so R8 cannot see the link from the
# class to it. Navigation3's six route keys and everything DataStore persists — ReaderPreferences and
# the core:common models — depend on that lookup: strip the serializer and the build still succeeds
# while navigation and restoring settings break at runtime. These are the conditional rules
# kotlinx.serialization documents, which also cover objects and nested serializers rather than only
# top-level classes.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Room's generated implementations are found from the abstract class name at runtime.
-keep class com.tedd.teddreader.core.room.*_Impl { *; }

# Enum entries are read back from stored strings (DocumentFormat, ReaderBlockKind and friends), so the
# names have to survive.
-keepclassmembers enum com.tedd.teddreader.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
