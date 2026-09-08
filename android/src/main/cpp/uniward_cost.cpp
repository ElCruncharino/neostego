// Steganography utility to hide messages into cover files
// Copyright (c) 2026 Nick Haghiri
//
// JNI marshaling only; the algorithm lives in uniward_cost_core.cpp (portable, host-testable).

#include <jni.h>

#include "uniward_cost_core.h"

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_elcruncharino_neostego_UniwardCostAccelerator_computeFlatNative(
    JNIEnv* env, jobject /*thiz*/, jdoubleArray planeFlatArr, jint planeH, jint planeW, jint blocksWide,
    jint blocksHigh, jintArray quantArr) {
    jsize planeLen = env->GetArrayLength(planeFlatArr);
    std::vector<double> plane(planeLen);
    env->GetDoubleArrayRegion(planeFlatArr, 0, planeLen, plane.data());

    int quant[64];
    env->GetIntArrayRegion(quantArr, 0, 64, quant);

    std::vector<double> cost = computeUniwardCost(plane.data(), planeH, planeW, blocksWide, blocksHigh, quant);

    jdoubleArray result = env->NewDoubleArray(static_cast<jsize>(cost.size()));
    env->SetDoubleArrayRegion(result, 0, static_cast<jsize>(cost.size()), cost.data());
    return result;
}
