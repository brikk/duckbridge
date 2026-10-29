// Standalone JNI reproduction. No Doris, JDBC driver, or external database required.
// Build with -DTEST_SIMDUTF_FIX and simdutf to exercise the proposed decoder too.
#include <jni.h>

#include <iomanip>
#include <iostream>
#include <stdexcept>
#include <string>
#include <utility>

#ifdef TEST_SIMDUTF_FIX
#include <simdutf.h>
#endif

static std::u16string code_units(JNIEnv* env, jstring value) {
    if (env->ExceptionCheck() || value == nullptr) throw std::runtime_error("JNI allocation failed");
    jsize length = env->GetStringLength(value);
    const jchar* chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("GetStringChars failed");
    std::u16string result;
    for (jsize i = 0; i < length; ++i) result.push_back(static_cast<char16_t>(chars[i]));
    env->ReleaseStringChars(value, chars);
    return result;
}

static void print_units(const char* label, const std::u16string& value) {
    std::cout << label << ':';
    for (char16_t unit : value) {
        std::cout << ' ' << std::hex << std::uppercase << std::setfill('0') << std::setw(4)
                  << static_cast<unsigned>(unit);
    }
    std::cout << std::dec << '\n';
}

#ifdef TEST_SIMDUTF_FIX
static jstring from_standard_utf8(JNIEnv* env, const std::string& input) {
    // The same length-delimited decoder/NewString sequence as the proposed Doris helper.
    std::u16string utf16(input.size(), u'\0');
    const auto converted = simdutf::convert_utf8_to_utf16_with_errors(
            input.data(), input.size(), utf16.data());
    if (converted.error != simdutf::SUCCESS) throw std::invalid_argument("Invalid UTF-8");
    return env->NewString(reinterpret_cast<const jchar*>(utf16.data()),
                          static_cast<jsize>(converted.count));
}

static void test_decoder(JNIEnv* env) {
    const std::pair<std::string, std::u16string> cases[] = {
            {"", u""}, {"ascii", u"ascii"}, {"stra\xC3\x9F" "e", u"stra\u00DFe"},
            {"a\xF0\x9F\x98\x80" "bc", u"a\U0001F600bc"},
            {std::string("a\0b", 3), std::u16string(u"a\0b", 3)},
            {"\xF4\x8F\xBF\xBF", u"\U0010FFFF"},
    };
    for (const auto& item : cases) {
        jstring value = from_standard_utf8(env, item.first);
        if (code_units(env, value) != item.second) throw std::runtime_error("Decoder changed a value");
        env->DeleteLocalRef(value);
    }
    const std::string invalid[] = {"\xF0\x9F", "\xC0\x80", "\xED\xA0\x80", "\xFF"};
    for (const auto& input : invalid) {
        bool rejected = false;
        try {
            jstring value = from_standard_utf8(env, input);
            env->DeleteLocalRef(value);
        } catch (const std::invalid_argument&) {
            rejected = true;
        }
        if (!rejected) throw std::runtime_error("Decoder accepted invalid standard UTF-8");
    }
    std::cout << "Proposed decoder: 6 valid cases preserved, 4 invalid encodings rejected\n";
}
#endif

int main() {
    JavaVMOption options[2];
    options[0].optionString = const_cast<char*>("-Xms16m");
    options[1].optionString = const_cast<char*>("-Xmx64m");
    JavaVMInitArgs args {};
    args.version = JNI_VERSION_1_8;
    args.nOptions = 2;
    args.options = options;
    JavaVM* vm = nullptr;
    JNIEnv* env = nullptr;
    if (JNI_CreateJavaVM(&vm, reinterpret_cast<void**>(&env), &args) != JNI_OK) return 2;
    int exit_code = 0;
    try {
        const std::string input = "a\xF0\x9F\x98\x80" "bc"; // ordinary UTF-8: 61 F0 9F 98 80 62 63
        const std::u16string expected = u"a\U0001F600bc";
        // This is exactly the API/input mismatch currently in Doris's map string conversion.
        jstring actual = env->NewStringUTF(input.c_str());
        const auto observed = code_units(env, actual);
        print_units("Expected UTF-16", expected);
        print_units("NewStringUTF(ordinary UTF-8)", observed);
        std::cout << (observed == expected ? "Unexpectedly matched on this JVM" : "BUG REPRODUCED") << '\n';
        env->DeleteLocalRef(actual);
        jstring correct = env->NewString(reinterpret_cast<const jchar*>(expected.data()),
                                        static_cast<jsize>(expected.size()));
        if (code_units(env, correct) != expected) throw std::runtime_error("NewString failed");
        env->DeleteLocalRef(correct);
#ifdef TEST_SIMDUTF_FIX
        test_decoder(env);
#endif
        if (observed == expected) exit_code = 3;
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        exit_code = 1;
    }
    vm->DestroyJavaVM();
    return exit_code;
}
