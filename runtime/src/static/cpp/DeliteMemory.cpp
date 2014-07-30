#include "DeliteMemory.h"

//TODO: pass the size of the heap as a parameter to DeliteHeapInit?
size_t DeliteHeapSize = 1024ULL * 1024ULL * 1024ULL * 32ULL; 
char **DeliteHeap;
size_t *DeliteHeapOffset;

pthread_mutex_t heapInitLock = PTHREAD_MUTEX_INITIALIZER;
pthread_cond_t heapInitCond = PTHREAD_COND_INITIALIZER;
int heapInitCnt = 0;

// align thread-local variables with 64 bytes to avoid false sharing
const int paddingShift = 6;

void delite_barrier(int numThreads) {
    pthread_mutex_lock(&heapInitLock);
    heapInitCnt += 1;
    while (heapInitCnt < numThreads) {
      pthread_cond_wait(&heapInitCond, &heapInitLock);
    }
    if (heapInitCnt == numThreads)
      pthread_cond_broadcast(&heapInitCond);
    pthread_mutex_unlock(&heapInitLock);
}

void DeliteHeapInit(int idx, int numThreads) {
  if (idx == 0) {
    DeliteHeap = new char*[numThreads << paddingShift];
    DeliteHeapOffset = new size_t[numThreads << paddingShift];
  }
  char *ptr = (char*)malloc(DeliteHeapSize/numThreads);
  memset(ptr, 0, DeliteHeapSize/numThreads);

  if (numThreads > 1) {
    delite_barrier(numThreads);
  }

  // Now all the threads allocated and initialized their own heap
  DeliteHeap[idx << paddingShift] = ptr;
  DeliteHeapOffset[idx << paddingShift] = 0;
  DHEAP_DEBUG("finished heap initialization for resource %d\n", idx);
}

void DeliteHeapClear(int idx, int numThreads) {
  delete[] DeliteHeap[idx << paddingShift];
  if (numThreads > 1) {
    delite_barrier(numThreads);
  }
  if (idx == 0) {
    delete[] DeliteHeap;
    delete[] DeliteHeapOffset;
  }
}

char *DeliteHeapAlloc(size_t sz, int idx) {
  char *ptr = DeliteHeap[idx << paddingShift] + DeliteHeapOffset[idx << paddingShift];
  DHEAP_DEBUG("DeliteHeapAlloc called for idx %d with size %d\n", idx, sz);
  //TODO: Need alignment for each type (passed as paramter)?
  size_t alignedSize = (sz+0x0008) & 0xFFFFFFF8;
  DeliteHeapOffset[idx << paddingShift] += alignedSize;
  return ptr;
}


