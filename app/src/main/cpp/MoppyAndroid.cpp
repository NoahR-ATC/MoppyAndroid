//
// Created by Noah on 2019-09-21.
//

#include <jni.h>
#include <string>

// Define short-form method names
#define JMethod(return_type) extern "C" JNIEXPORT return_type JNICALL // JNI Method syntax
#define GetString Java_com_example_moppyandroid_MainActivty_GetString
#define GetStringJ Java_com_example_moppyandroid_MainActivty_GetStringJ

// Define methods
JMethod(jstring) GetString(JNIEnv* env, jobject obj) {
    return env->NewStringUTF("Hello from C++");
}

void test();
Method(test,void,void)
JMethod(jstring) GetStringJ (JNIEnv* env, jobject obj, jobject str){
    jclass strClass = env->FindClass("java/lang/String");
    if(strClass==nullptr) { throw; }
    if( env->GetObjectClass(str) != strClass) { env->DeleteLocalRef(strClass); throw; }

    std::string result(env->GetStringUTFChars(static_cast<jstring>(str), nullptr));
    result += " - C++";

    env->DeleteLocalRef(strClass);
    return env->NewStringUTF(result.c_str());
}
