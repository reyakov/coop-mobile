-dontwarn com.sun.jna.**

-keep class com.sun.jna.** { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Callback { *; }

-keep class rust.nostr.sdk.** { *; }
-keep class su.reya.nostr.** { *; }

-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations