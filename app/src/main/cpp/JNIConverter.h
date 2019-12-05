//
// Created by Noah on 2019-09-24.
// This header defines a class used to cast between JNIObjects and native objects
//

#ifndef MOPPYAndroid_JNICONVERTER_H
#define MOPPYAndroid_JNICONVERTER_H

#ifndef JNI_H_
#include <jni.h>
#endif

class JNIConverter {
public:
    static int Int(jint j) { return (int)j; }
    static int Int(jint j, int& n) { n = (int)j; return n; }
};

#endif // End MOPPYAndroid_JNICONVERTER_H
