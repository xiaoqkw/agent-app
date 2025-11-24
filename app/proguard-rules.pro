# Keep NanoHTTPD reflection metadata minimal while avoiding obfuscation issues.
-dontwarn org.nanohttpd.**
# Accessibility service classes should not be obfuscated to ease debugging.
-keep class com.example.agent.** { *; }
