#include <stdio.h>

#define TILE_SIZE 16

__device__ int findPosition(const int *a, int k, int b, int top){
	if(b < 0){
		return -1;
	}
	if(b > top){
		return -2;
	}
	for(int i = 0; i < k; i++){
		if(a[i] == b){
			return i;
		}	
	}
	return -3;
}

__global__ void parallelBFE(const int *x, const int *y, int *g, const int *a, const int *b, int n, int k, int M, int N, int E, int *N_DISKS){
	//int t = blockIdx.x * blockDim.x + threadIdx.x;
	int t = threadIdx.x;
	__shared__ int px[1000];
	__shared__ int py[1000];
	int j = 0;
	int h;

	// Center-Medium
	int cm = a[t];
	for(int i = b[t]; i < b[t + 1]; i++){
		px[j] = x[i];
		py[j] = y[i];
		j++;
	}
	h = j;

	// Left-Medium
	int lm;
	if(cm % M == 0){
		lm = -1;
	} else {
		lm = findPosition(a, k, cm - 1, M*N);
	}
	if(lm >= 0){
		for(int i = b[lm]; i < b[lm + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Right-Medium
	int rm;
	if(cm % M == M - 1){
		rm = -1;
	} else {
		rm = findPosition(a, k, cm + 1, M*N);
	}
	if(rm >= 0){
		for(int i = b[rm]; i < b[rm + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Center-Up
	int cu = cm - M;
	cu = findPosition(a, k, cu, M*N);
	if(cu >= 0){
		for(int i = b[cu]; i < b[cu + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Left-Up
	int lu;
	if(cm % M == 0){
		lu = -1;
	} else {
		lu = findPosition(a, k, cm - M - 1, M*N);
	}
	if(lu >= 0){
		for(int i = b[lu]; i < b[lu + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Right-Up
	int ru;
	if(cm % M == M - 1){
		ru = -1;
	} else {
		ru = findPosition(a, k, cm - M + 1, M*N);
	}
	if(ru >= 0){
		for(int i = b[ru]; i < b[ru + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Center-Down
	int cd = cm + M;
	cd = findPosition(a, k, cd, M*N);
	if(cd >= 0){
		for(int i = b[cd]; i < b[cd + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Left-Down
	int ld;
	if(cm % M == 0){
		ld = -1;
	} else {
		ld = findPosition(a, k, cm + M - 1, M*N);
	}
	if(ld >= 0){
		for(int i = b[ld]; i < b[ld + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	// Right-Down
	int rd;
	if(cm % M == M - 1){
		rd = -1;
	} else {
		rd = findPosition(a, k, cm + M + 1, M*N);
	}
	if(rd >= 0){
		for(int i = b[rd]; i < b[rd + 1]; i++){
			px[j] = x[i];
			py[j] = y[i];
			j++;
		}
	}
	//__syncthreads();
	N_DISKS[t] = j - h;
}


__global__ void mysgemm(int m, int n, int k, const float *A, const float *B, float *C) {
    // Declaring the variables in shared memory...
	__shared__ float A_s[TILE_SIZE][TILE_SIZE];
	__shared__ float B_s[TILE_SIZE][TILE_SIZE];

	// Finding the coordinates for the current thread...
	int tx = threadIdx.x;
	int ty = threadIdx.y;
	int col = blockIdx.x * blockDim.x + tx;
	int row = blockIdx.y * blockDim.y + ty;

	float sum = 0.0f;

	for(int i = 0; i < ((k - 1) / TILE_SIZE) + 1; ++i){
		// Validation in the case the thread tries to write in share 
		// memory a value outside the dimensions of matrix A...
		if(row < m && (i * TILE_SIZE + tx) < k){
			A_s[ty][tx] = A[(row * k) + (i * TILE_SIZE + tx)];
		} else {
			// In that case, just write a 0 which will no affect 
			// the computation...
			A_s[ty][tx] = 0.0f;
		}
		// Similar validation for B...
		if((i * TILE_SIZE + ty) < k && col < n){
			B_s[ty][tx] = B[((i * TILE_SIZE + ty) * n) + col];
		} else {
			B_s[ty][tx] = 0.0f;
		}
		// Wait for all the threads to write in share memory
		__syncthreads();

		// Compute the multiplication on the tile...
		for(int j = 0; j < TILE_SIZE; ++j){
			sum += A_s[ty][j] * B_s[j][tx];
		}
		// Wait to finish before to go ahead with the next phase...
		__syncthreads();
	}
	// Write the final result in C just if it is inside of the valid 
	// dimensions... 
	if(row < m && col < n){
		C[row * n + col] = sum;
	}
}

void basicSgemm(char transa, char transb, int m, int n, int k, float alpha, const float *A, int lda, const float *B, int ldb, float beta, float *C, int ldc)
{
    const unsigned int BLOCK_SIZE = TILE_SIZE;

    // Initialize thread block and kernel grid dimensions
    const dim3 dim_block(BLOCK_SIZE, BLOCK_SIZE, 1);
    const dim3 dim_grid(((n - 1) / BLOCK_SIZE) + 1, ((m - 1) / BLOCK_SIZE) + 1, 1);

    // Calling the kernel with the above-mentioned setting... 
    mysgemm<<<dim_grid, dim_block>>>(m, n, k, A, B, C);
}