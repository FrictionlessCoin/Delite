#ifndef _DELITE_CONFIG_
#define _DELITE_CONFIG_

#include <iostream>
#include <jni.h>
#include <numa.h>
#include <mutex>         
#include "Config.h"

Config* config = 0;
std::mutex init_mtx;

// heavy-handed, but doesn't appear there is another good way
int getNumCores(int numSockets) {
    FILE* pipe = popen("grep cores /proc/cpuinfo | head -1 | cut -d ':' -f 2 | tr -d ' '", "r");
    if (!pipe) return -1;

    char buffer[128];
    std::string result = "";
    while(!feof(pipe)) {
        if(fgets(buffer, 128, pipe) != NULL)
            result += buffer;
    }
    pclose(pipe);

    try {	
        int coresPerSocket = std::stoi(result);
	return coresPerSocket*numSockets;
    }
    catch(std::exception const &e) {
	return -1;
    }
}

void initializeConfig(int numThreads) {
    config = new Config(numThreads);
    if (numa_available() >= 0) {
        config->numSockets = numa_num_configured_nodes();
        config->numCores = getNumCores(config->numSockets);
	if (config->numCores == -1)
	    config->numCores = numa_num_configured_cpus();
    }
    // printf("numThreads: %d\n", config->numThreads);
    // printf("numCores: %d\n", config->numCores);
    // printf("numSockets: %d\n", config->numSockets);
}


extern "C" JNIEXPORT void JNICALL Java_ppl_delite_runtime_executor_AccExecutionThread_initializeThread(JNIEnv* env, jobject obj, jint threadId, jint numThreads);

JNIEXPORT void JNICALL Java_ppl_delite_runtime_executor_AccExecutionThread_initializeThread(JNIEnv* env, jobject obj, jint threadId, jint numThreads) {
    init_mtx.lock();
    if (!config) {
        initializeConfig(numThreads);
    }
    init_mtx.unlock();

    if (numa_available() >= 0) {
        int socketId = config->threadToSocket(threadId);
        if (socketId < numa_num_configured_nodes()) {
            printf("binding thread %d to socket %d\n", threadId, socketId);
            bitmask* nodemask = numa_allocate_nodemask();
            numa_bitmask_setbit(nodemask, socketId);
            numa_bind(nodemask);
        }
    }
}

#endif

