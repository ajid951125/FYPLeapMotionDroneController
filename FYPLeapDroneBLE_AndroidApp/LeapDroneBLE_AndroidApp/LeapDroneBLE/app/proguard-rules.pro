-keepattributes Exceptions,InnerClasses,*Annotation*,Signature,EnclosingMethod

-dontwarn android.support.v4.**
-dontwarn com.dji.**
-dontwarn dji.**
-dontwarn java.**
-dontwarn okio.**
-dontwarn org.bouncycastle.**
-dontwarn sun.**

-keepnames class * implements java.io.Serializable

-keepclassmembers enum * {
    public static <methods>;
}
-keepclassmembers public class * extends android.view.View {
    void set*(***);
    *** get*();
}
-keepclassmembers class * extends android.app.Service
-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

-keepclasseswithmembers,allowshrinking class * {
    native <methods>;
}

-keep class * extends android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keep,allowshrinking class * extends dji.publics.DJIUI.** {
    public <methods>;
}
-keep,allowshrinking class org.** { *; }
-keep class android.support.** { *; }
-keep class android.media.** { *; }
-keep class android.support.v4.app.** { *; }
-keep class android.support.v4.** { *; }
-keep class android.support.v7.widget.SearchView { *; }
-keep class com.dji.** { *; }
-keep class com.secneo.** { *; }
-keep class com.lmax.disruptor.** {*; }
-keep class com.squareup.wire.** { *; }
-keep class com.google.** { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.* { *; }
-keep class dji.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class okio.** { *; }
-keep class sun.misc.Unsafe { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keep interface android.support.v4.app.** { *; }
