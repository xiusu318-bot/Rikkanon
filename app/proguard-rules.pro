# =================================================================================================
# 插件「能力完全开放」所需的保留规则（rikka.java 反射桥 / rikka.hook 方法 Hook）
#
# 插件可以通过反射按名字调用宿主任意类与成员，也可以 Hook 宿主任意方法。
# R8 默认会删除未使用的成员、内联小方法、合并类、改写方法签名，这些都会让插件「按名字找不到」
# 或「Hook 了但不生效」。因此宿主自身代码全部保留（类、成员、签名都不改动）。
# 代价：APK 略大、宿主代码少一部分 R8 优化；功能与行为不受影响。
# =================================================================================================

# 宿主全部代码（app 及各模块均位于 me.rerere 包下）
# 排除图标库 hugeicons-compose（外部依赖，包名同为 me.rerere.hugeicons，约 6000 个图标类）：
# 它照常由 R8 只保留 App 实际用到的图标，否则会把全部图标打进 APK（约 +11MB）。
# allowshrinking：App 从未用到的代码仍会被删除（连带它们引用的第三方库代码），
# 但只要代码还在，就保持原样：不改名、不改签名、不内联、不合并，反射与 Hook 都能按名字找到。
-keep,allowshrinking class !me.rerere.hugeicons.**, me.rerere.** { *; }
-keep,allowshrinking interface !me.rerere.hugeicons.**, me.rerere.** { *; }
-keep,allowshrinking enum !me.rerere.hugeicons.**, me.rerere.** { *; }
-keep,allowshrinking class kotlin.uuid.** { *; }

# CUSTOM_UI_DOCS.md 与 PLUGIN_API_DOC.md 公开承诺的数据字典、服务、ViewModel 与可借用 Compose 控件：
# 严禁 allowshrinking 裁掉宿主自身未直接调用但文档列出的扩展函数/Getter（如 SettingsExtKt.getCurrentChatModel、DefaultSettingsKt.getDEFAULT_ASSISTANTS_IDS）
-keep class me.rerere.rikkahub.data.datastore.** { *; }
-keep class me.rerere.rikkahub.data.model.** { *; }
-keep class me.rerere.rikkahub.data.repository.** { *; }
-keep class me.rerere.rikkahub.data.files.** { *; }
-keep class me.rerere.rikkahub.data.ai.mcp.** { *; }
-keep class me.rerere.rikkahub.data.skills.** { *; }
-keep class me.rerere.rikkahub.service.ChatService { *; }
-keep class me.rerere.rikkahub.ui.pages.assistant.AssistantVM { *; }
-keep class me.rerere.rikkahub.ui.pages.setting.SettingVM { *; }
-keep class me.rerere.rikkahub.ui.pages.history.HistoryVM { *; }
-keep class me.rerere.rikkahub.ui.pages.favorite.FavoriteVM { *; }
-keep class me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceVM { *; }
-keep class me.rerere.rikkahub.ui.components.ui.QRCodeKt { *; }
-keep class me.rerere.rikkahub.ui.components.ui.QRCodeScannerKt { *; }
-keep class me.rerere.rikkahub.ui.pages.assistant.detail.AssistantImporterKt { *; }
-keep class me.rerere.rikkahub.ui.components.ai.ModelListKt { *; }
-keep class me.rerere.rikkahub.ui.components.ui.VideoPlayerDialogKt { *; }
-keep class me.rerere.rikkahub.ui.pages.extensions.workspace.WorkspaceTerminalPageKt { *; }
-keep class me.rerere.ai.provider.** { *; }
-keep class me.rerere.ai.core.** { *; }
-keep class me.rerere.mediagen.provider.** { *; }
-keep class me.rerere.tts.provider.** { *; }
-keep class me.rerere.asr.** { *; }
-keep class me.rerere.search.** { *; }
-keep class me.rerere.plugin.host.** { *; }
-keep class kotlin.uuid.Uuid { *; }
-keep class kotlin.uuid.Uuid$Companion { *; }

# 反射需要的元数据：泛型签名、内部类关系、注解、Kotlin 元数据（参数名、默认参数等）
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,MethodParameters,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keep class kotlin.Metadata { *; }

# Pine 与 Navigation3（UIPageHost 读取 NavEntry.key）
-keep class top.canyie.pine.** { *; }
-keepclassmembers class androidx.navigation3.runtime.NavEntry { private final java.lang.Object key; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn top.canyie.pine.**

# 全保留宿主代码后，部分第三方库里原本被裁掉的可选功能路径变得可达，
# 它们引用的服务端/桌面 JVM 类在 Android 上本来就不存在，运行时也不会走到这些路径。
-dontwarn com.github.benmanes.caffeine.cache.**
-dontwarn jakarta.servlet.**
-dontwarn javax.servlet.**
-dontwarn javax.script.**

# 界面自定义的 Compose(...) 节点：宿主界面函数执行中抛异常时，按字段名恢复 Compose 运行时内部的进行中记录
# （见 ui/custom/UIHostNodes.kt 的 UIComposerRecovery）。同时保住类本身防止 R8 Full Mode 合并类或改写 IntStack 字段类型。
-keep,allowshrinking class androidx.compose.runtime.GapComposer {
    java.util.ArrayList invalidateStack;
    androidx.compose.runtime.IntStack providersInvalidStack;
    boolean providersInvalid;
    androidx.compose.runtime.PersistentCompositionLocalMap providerCache;
}
-keep,allowshrinking class androidx.compose.runtime.LinkComposer {
    java.util.ArrayList invalidateStack;
    androidx.compose.runtime.IntStack providersInvalidStack;
    boolean providersInvalid;
    androidx.compose.runtime.PersistentCompositionLocalMap providerCache;
}
-keep,allowshrinking class androidx.compose.runtime.IntStack {
    int tos;
}
