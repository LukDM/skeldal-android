# SDLActivity is called from native code by name
-keep class org.libsdl.app.** { *; }

# NativeBridge.onTextInput is called from native code (JNI_OnLoad looks it up by name)
-keep class cz.gates.skeldal.NativeBridge { *; }
