//
// Created by Noah on 2019-09-21.
//

// **** NOTE ****
// Due to some stupidity with AndroidStudio, you must have Java auto-create the native function
// and then replace the line with the JNIPrefix macro otherwise it will not be recognized

#include <jni.h>
#include <string>
#include "JNIConverter.h"

#define JNIPrefix(return_type) extern "C" JNIEXPORT return_type JNICALL

// Macros that don't consistently work because of some silliness with the Java linking
/*
#define PATH Java_com_example_moppyandroid_MainActivity_
#define FULLPATH2(x,y) x##y
#define FULLPATH(x,y) FULLPATH2(x,y)
#define COMBINEPATH(name) FULLPATH(PATH,name)

#define Method(name, return_type) extern "C" JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj) { return name(env, obj); }

#define MethodParam(name, return_type)\
extern "C" \
JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj, jobjectArray param_list)\
{ return name(env, obj, param_list); }

// Non-working macro that could perhaps be fixed with enough thought and preprocessor magic
//#define Method(name, return_type, args...) extern "C" JNIEXPORT return_type JNICALL COMBINEPATH(name) (JNIEnv* env, jobject obj,  args) { return name(args); }
*/

jstring GetString(JNIEnv* env, jobject thiz) {
    return env->NewStringUTF("Hello from C++");
}

jstring GetStringEdited (JNIEnv* env, jobject thiz, jstring str){
    /*jclass strClass = env->FindClass("java/lang/String");
    if(strClass==nullptr) { throw; }
    if( env->GetObjectClass(passed_object) != strClass) { env->DeleteLocalRef(strClass); throw; }
    */

    std::string result(env->GetStringUTFChars(static_cast<jstring>(str), nullptr));
    result += " - C++";

    //env->DeleteLocalRef(strClass);
    return env->NewStringUTF(result.c_str());
}

// Declare java method forwarders
JNIPrefix(jstring) Java_com_moppyandroid_main_MainActivity_GetString(JNIEnv *env, jobject thiz)
    { return GetString(env, thiz); }
JNIPrefix(jstring) Java_com_moppyandroid_main_MainActivity_GetStringEdited(JNIEnv *env, jobject thiz, jstring str)
    { return GetStringEdited(env, thiz, str); }extern "C"
    JNIEXPORT jobjectArray JNICALL
    Java_com_fazecast_jSerialComm_SerialPort_getCommPorts(JNIEnv *env, jclass clazz) {
        Java_com_fazecast_jSerialComm_SerialPort_getCommPorts(env, clazz);    }