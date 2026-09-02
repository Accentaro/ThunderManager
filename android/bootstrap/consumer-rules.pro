-keep public class dev.thunder.bootstrap.ThunderAppComponentFactory { public <init>(); }
-keep public class dev.thunder.bootstrap.ThunderRecoveryActivity { public <init>(); }
-keep public class dev.thunder.bootstrap.ThunderReactNativeBridge { public static void loadJSBundle(java.lang.Object, java.lang.Object); }
-keep public class dev.thunder.bootstrap.ThunderRuntimeEntrypoint { public <init>(); }
-keep public class dev.thunder.bootstrap.ThunderChatBubblesBridge {
    public static void thunderBeforeConfigureAccessoriesMargin(java.lang.Object);
    public static void thunderAfterConfigureAccessoriesMargin(java.lang.Object);
    public static void thunderBeforeConfigureAuthor(java.lang.Object);
    public static void thunderAfterConfigureAuthor(java.lang.Object);
    public static void beforeConfigureAccessoriesMargin(java.lang.Object);
    public static void afterConfigureAccessoriesMargin(java.lang.Object);
    public static void beforeConfigureAuthor(java.lang.Object);
    public static void afterConfigureAuthor(java.lang.Object);
}
-keep public interface dev.thunder.bootstrap.EarlyRuntimeEntrypoint
