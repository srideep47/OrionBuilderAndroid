# MNN JNI: native methods bind by exact class+method name. Keep the engine classes intact.
-keep class com.localai.llm.** { *; }
-keep class com.localai.qnn.** { *; }
-keep class com.legion.orion.engine.mnn.** { *; }

# JNI callbacks invoked from native code.
-keep interface com.localai.llm.GenerateProgressListener { *; }
-keep class com.localai.llm.AudioDataListener { *; }

# Gson model config (de)serialization via reflection.
-keep class com.localai.modelsettings.** { *; }
-keep class com.localai.benchmark.** { *; }
-keepattributes Signature, *Annotation*

# Room / Hilt generated code is handled by their own consumer rules.
