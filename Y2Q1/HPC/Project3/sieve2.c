/*
 * Sieve of Eratosthenes
 *
 * Programmed by Michael J. Quinn
 *
 * Last modification: 7 September 2001
 */
#include "mpi.h" 
#include <math.h> 
#include <stdio.h>
#include <stdlib.h> 
#define MIN(a,b) ((a)<(b)?(a):(b))

int main (int argc, char *argv[]) {
	unsigned int count; /* Local prime count */
	double elapsed_time; /* Parallel execution time */
	unsigned long  first; /* Index of first multiple */
	unsigned int global_count; /* Global prime count */
	unsigned long long high_value; /* Highest value on this proc */
	unsigned long long i;
	int id; /* Process ID number */
	unsigned long index; /* Index of current prime */
	unsigned long long low_value; /* Lowest value on this proc */
	char *marked; /* Portion of 2,...,'n' */
	unsigned long long int  n; /* Sieving from 2, ..., 'n' */
	int p; /* Number of processes */
	unsigned long proc0_size; /* Size of proc 0's subarray */
	unsigned long prime; /* Current prime */
	unsigned long kprime; /* Prime in marked0 */
	unsigned long long size; /* Elements in 'marked' */
	unsigned long long n_size; /* Number of odds between 3 to n */
	unsigned long sqrtn; /* Square root of n */
	char *marked0; /* Primes in between 3 to sqrt(n) */

	MPI_Init (&argc, &argv);
	/* Start the timer */
	MPI_Comm_rank (MPI_COMM_WORLD, &id);
	MPI_Comm_size (MPI_COMM_WORLD, &p);
	MPI_Barrier(MPI_COMM_WORLD);
	elapsed_time = -MPI_Wtime();
	if (argc != 2) {
		if (!id) printf ("Command line: %s <m>\n", argv[0]);
		MPI_Finalize();
		exit (1);
	}
	/* Read N as a unsigned long long from the arguments */
	char *e;
	n = strtoull(argv[1], &e, 10);

	/* Compute number of odds between 3 to n */
	n_size = (n + 1) / 2;

	/* Finding how many primes from 3 to sqrt(n) and allocating space*/
	sqrtn = ceil(sqrt((double) n))/2;
	marked0 = (char *) malloc(sqrtn + 1);
	for(i = 0; i <=sqrtn; i++) marked0[i] = 0;
	index = 0;

	/* Finding the primes and store them in marked0 */
	kprime = 3;
	do{
		first = (kprime * kprime - 3) / 2;
		for(i = first; i <= sqrtn; i += kprime) marked0[i] = 1;
		while(marked0[++index]);
		kprime = 2 + (2 * index + 1);
	} while(kprime * kprime <= sqrtn);
   
   /* Figure out this process's share of the array, as
      well as the integers represented by the first and
      last array elements */
      
    /* Adjust the formula to remove the even positions */
	low_value  = 2 + (2 * (id * (n_size - 1) / p)) + 1;
	high_value = 2 + (2 * (((id + 1) * (n_size - 1) / p) - 1)) + 1;
	size = (high_value - low_value + 1) / 2;

	/* Bail out if all the primes used for sieving are
	  not all held by process 0 */
	proc0_size = (n_size - 1) / p;
	if ((2 + (2 * proc0_size) + 1) < (int) sqrt((double) n)) {
		if (!id) printf ("Too many processes\n");
		MPI_Finalize();
		exit (1);
	}
	/* Allocate this process's share of the array. */
	marked = (char *) malloc (size);
	if (marked == NULL) {
		printf ("Cannot allocate enough memory\n");
		MPI_Finalize();
		exit (1);
	}
	for (i = 0; i <= size; i++) marked[i] = 0;
	index = 0;
	/* Start from 3 */
	prime = 3;
	do {
		/* Divide by 2 to manage only odd positions */
		if (prime * prime > low_value)
			first = (prime * prime - low_value) / 2;
		else {
			if (!(low_value % prime)) first = 0;
			else{
				first = prime - (low_value % prime);
				if(!(first % 2)) first = first / 2;
				else first = (first + prime) / 2; 
			}
		}
		for (i = first; i <= size; i += prime){
			marked[i] = 1;
		}
		/* Pick up the next prime from marked0 */
		while(marked0[++index]);
		prime = 2 + (2 * index + 1);
		/* We do not need the Bcast anymore */
	} while (prime * prime <= n);
	count = 0;
	for (i = 0; i <= size; i++)
		if (!marked[i]){
		  count++;
		}

	if (p > 1) MPI_Reduce (&count, &global_count, 1, MPI_INT, MPI_SUM, 0, MPI_COMM_WORLD);
	/* Stop the timer */
	elapsed_time += MPI_Wtime();
	/* Print the results */
	if (!id) {
		global_count++;  //Counting 2 as prime...
		printf("S2, %llu, %d, %d, %10.6f\n", n, p, global_count, elapsed_time);
	}
	MPI_Finalize ();
	return 0;
}
