#ifndef _DELITE_CONFIG_
#define _DELITE_CONFIG_

#include <stdlib.h>
#include <iostream>
#include <jni.h>
#include <pthread.h>
#include <sched.h>
#include "Config.h"
#include "DeliteDatastructures.h"
#include "DeliteCpp.h"

#ifdef __DELITE_CPP_NUMA__
#include <numa.h>
#endif

#ifdef __sun
#include <sys/processor.h>
#endif


Config* config = NULL;
resourceInfo_t* resourceInfos = NULL;
pthread_mutex_t init_mtx = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t init_cond = PTHREAD_COND_INITIALIZER;

// heavy-handed, but doesn't appear there is another good way
int getCpuInfo(FILE* pipe) {
  if (!pipe) return -1;

  char buffer[128];
  buffer[0] = 0;
  fgets(buffer, 128, pipe);
  pclose(pipe);
  return atoi(buffer);
}

int getNumCoresPerSocket() {
  FILE* pipe = popen("grep 'cores' /proc/cpuinfo 2> /dev/null | head -1 | cut -d ':' -f 2 | tr -d ' '", "r");
  //return getCpuInfo(pipe);
  return 12;
}

int getNumSockets() {
  FILE* pipe = popen("grep 'physical id' /proc/cpuinfo 2> /dev/null | tail -1 | cut -d ':' -f 2 | tr -d ' '", "r");
  //return getCpuInfo(pipe) + 1;
  return 4;
}

void initializeConfig(int numThreads) {
  config = new Config(numThreads);
    
  //detect physical config
  int numSockets = getNumSockets();
  int numCoresPerSocket = getNumCoresPerSocket();
  int numCores = numCoresPerSocket * numSockets;

  //detect numa config
  #ifdef __DELITE_CPP_NUMA__
  if (numa_available() >= 0) {
    int numCpus = numa_num_configured_cpus();
    if (numCoresPerSocket <= 0) {
        VERBOSE("WARNING: Unable to automatically determine number of physical cores, assuming %d\n", numCpus);
        numCores = numCpus;
    }

    int numNodes = numa_num_configured_nodes();
    if (numSockets > 0 && numSockets != numNodes) {
      VERBOSE("WARNING: Found %d sockets but %d NUMA nodes. Using %d nodes\n", numSockets, numNodes, numNodes);
    }
    numSockets = numNodes;
    numCoresPerSocket = numCores / numSockets; //potentially re-distribute cores across nodes
  }
  #endif

  if (numSockets > 0 && numCoresPerSocket > 0) {
    config->numSockets = numSockets;
    config->numCoresPerSocket = numCoresPerSocket;
    VERBOSE("Detected machine configuration of %d socket(s) with %d core(s) per socket.\n", config->numSockets, config->numCoresPerSocket);
  }
  else {
    VERBOSE("WARNING: Unable to automatically detect machine configuration.  Assuming %d socket(s) with %d core(s) per socket.\n", config->numSockets, config->numCoresPerSocket);
  }
}

void initializeGlobal(int numThreads, size_t heapSize) {
  pthread_mutex_lock(&init_mtx); 
  if (!config) {
    initializeConfig(numThreads);
    resourceInfos = new resourceInfo_t[numThreads];
    for (int i=0; i<numThreads; i++) {
      resourceInfos[i].threadId = i;
      resourceInfos[i].numThreads = numThreads;
      resourceInfos[i].socketId = config->threadToSocket(i);
      resourceInfos[i].numSockets = config->numSockets;
      resourceInfos[i].rand = new DeliteCppRandom();
    }
    DeliteHeapInit(numThreads, heapSize);
  }
  pthread_mutex_unlock(&init_mtx);
}

void freeGlobal(int numThreads) {
  pthread_mutex_lock(&init_mtx);
  if (config) {
    DeliteHeapClear(numThreads);
    delete[] resourceInfos;
    delete config;
    config = NULL;
  }
  pthread_mutex_unlock(&init_mtx);
}

void initializeThread(int threadId) {
  #ifdef __linux__
    cpu_set_t cpu;
    CPU_ZERO(&cpu);
    CPU_SET(threadId, &cpu);
    sched_setaffinity(0, sizeof(cpu_set_t), &cpu);
        
    #ifdef __DELITE_CPP_NUMA__
      if (numa_available() >= 0) {
        int socketId = config->threadToSocket(threadId);
        if (socketId < numa_num_configured_nodes()) {
          bitmask* nodemask = numa_allocate_nodemask();
          numa_bitmask_setbit(nodemask, socketId);
          numa_set_membind(nodemask);
        }
        //VERBOSE("Binding thread %d to cpu %d, socket %d\n", threadId, threadId, socketId);
      }
    #endif
  #endif

  #ifdef __sun
    processor_bind(P_LWPID, P_MYID, threadId, NULL);
  #endif
}

void initializeAll(int threadId, int numThreads, int numLiveThreads, size_t heapSize) {
  initializeGlobal(numThreads, heapSize);
  initializeThread(threadId);
  delite_barrier(numLiveThreads); //ensure fully initialized before any continue
}

void clearAll(int numThreads, int numLiveThreads) {
  delite_barrier(numLiveThreads); //first wait for all threads to arrive
  freeGlobal(numThreads);
}

#endif
