# Keep the entire core steganography/crypto module intact. It is small, and its plugins, config
# objects and label resources are looked up by name (LabelUtil / ResourceBundle, plugin registration),
# so shrinking or renaming it risks breaking embed/extract or decryption of existing files.
-keep class com.openstego.desktop.** { *; }
-keepclassmembers class com.openstego.desktop.** { *; }

# Resource bundle keys are referenced as strings; keep attributes R8 might otherwise drop.
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# Compose and AndroidX ship their own consumer rules; nothing extra needed here.
