#include <jni.h>
#include <string>

#define PATH Java_com_example_ctest_MainActivity_
#define FULLPATH2(x,y) x##y
#define FULLPATH(x,y) FULLPATH2(x,y)
#define COMBINEPATH(name) FULLPATH(PATH,name)

#define Method(name, return_type)\
extern "C" \
JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj)\
{ return name(env, obj); }

#define MethodParam(name, return_type, param_list)\
JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj, jObjArray param_list)\
{ return name(env, obj, param_list); }

// Non-working macro that could perhaps be fixed with enough thought and preprocessor magic
//#define Method(name, return_type, args...) extern "C" JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj,  args) { return name(args); }

// **** ORIGINAL ****
//..extern "C" JNIEXPORT jstring JNICALL
//Java_com_example_ctest_MainActivity_stringFromJNI(
//        JNIEnv* env,
//        jobject /* this */) {
//    std::string hello = "Hello from C++";
//    return env->NewStringUTF(hello.c_str());
//}*/

//extern "C" JNIEXPORT jstring JNICALL Java_com_example_ctest_MainActivity_StringFromJNI(JNIEnv* env, jobject obj){
jstring StringFromJNI(JNIEnv* env, jobject obj){
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

jstring GetString(JNIEnv* env, jobject obj) { return env->NewStringUTF("C++ Here!");}
//extern "C" JNIEXPORT jstring JNICALL Java_com_example_ctest_MainActivity_GetString(JNIEnv* env, jobject obj) { return env->NewStringUTF("C++ Here!");}

// Create java method aliases
Method(StringFromJNI, jstring);
Method(GetString, jstring);
