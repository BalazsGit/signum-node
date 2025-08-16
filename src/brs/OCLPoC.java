package brs;

import brs.props.PropertyService;
import brs.props.Props;
import brs.services.BlockService;
import brs.util.MiningPlot;
import org.jocl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.Locale;

import static org.jocl.CL.*;

class OCLPoC {
    private OCLPoC() {
    }

    private static Logger logger = LoggerFactory.getLogger(OCLPoC.class);

    private static int maxConfiguredHashesPerEnqueue;
    private static final AtomicInteger dynamicHashesPerEnqueue = new AtomicInteger();
    private static int MEM_PERCENT;

    private static cl_context ctx;
    private static cl_command_queue queue;
    private static cl_program program;
    private static cl_kernel genKernel;
    private static cl_kernel getKernel;
    private static cl_kernel getKernel2;

    private static long maxItems;
    private static long MAX_GROUP_ITEMS;

    private static Object oclLock = new Object();

    private static long BUFFER_PER_ITEM = (long) MiningPlot.PLOT_SIZE + 16;
    private static long MEM_PER_ITEM = 8 // id
            + 8 // nonce
            + BUFFER_PER_ITEM // buffer
            + 4 // scoop num
            + MiningPlot.SCOOP_SIZE; // output scoop

    static { // Initialize fields that do not depend on the OCL context
        PropertyService propertyService = Signum.getPropertyService();
        maxConfiguredHashesPerEnqueue = propertyService.getInt(Props.GPU_HASHES_PER_BATCH);
        dynamicHashesPerEnqueue.set(maxConfiguredHashesPerEnqueue);
        MEM_PERCENT = propertyService.getInt(Props.GPU_MEM_PERCENT);
    }

    /**
     * Initializes the OpenCL context, command queue, and kernels.
     * This method is synchronized to prevent race conditions during initialization.
     * It is designed to be idempotent; if the context is already initialized, it
     * does nothing.
     * If initialization fails, it cleans up any partially created resources and
     * throws an exception.
     */
    public static synchronized void init() {
        if (ctx != null) {
            return; // Already initialized
        }

        PropertyService propertyService = Signum.getPropertyService();
        if (!propertyService.getBoolean(Props.GPU_ACCELERATION)) {
            return; // Do not initialize if GPU acceleration is disabled
        }

        try {
            boolean autoChoose = propertyService.getBoolean(Props.GPU_AUTODETECT);
            setExceptionsEnabled(true);

            int platformIndex;
            int deviceIndex;
            if (autoChoose) {
                AutoChooseResult ac = autoChooseDevice();
                if (ac == null) {
                    throw new OCLCheckerException("Autochoose failed to select a GPU");
                }
                platformIndex = ac.getPlatform();
                deviceIndex = ac.getDevice();
                logger.info("Choosing Platform {} - DeviceId: {}", platformIndex, deviceIndex);
            } else {
                platformIndex = propertyService.getInt(Props.GPU_PLATFORM_IDX);
                deviceIndex = propertyService.getInt(Props.GPU_DEVICE_IDX);
            }

            int[] numPlatforms = new int[1];
            clGetPlatformIDs(0, null, numPlatforms);

            if (numPlatforms[0] == 0) {
                throw new OCLCheckerException("No OpenCL platforms found");
            }

            if (numPlatforms[0] <= platformIndex) {
                throw new OCLCheckerException("Invalid OpenCL platform index");
            }

            cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
            clGetPlatformIDs(platforms.length, platforms, null);

            cl_platform_id platform = platforms[platformIndex];

            int[] numDevices = new int[1];
            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, 0, null, numDevices);

            if (numDevices[0] == 0) {
                throw new OCLCheckerException("No OpenCl Devices found");
            }

            if (numDevices[0] <= deviceIndex) {
                throw new OCLCheckerException("Invalid OpenCL device index");
            }

            cl_device_id[] devices = new cl_device_id[numDevices[0]];
            clGetDeviceIDs(platform, CL_DEVICE_TYPE_GPU, devices.length, devices, null);

            cl_device_id device = devices[deviceIndex];

            if (!checkAvailable(device)) {
                throw new OCLCheckerException("Chosen GPU must be available");
            }

            if (!checkLittleEndian(device)) {
                throw new OCLCheckerException("Chosen GPU must be little endian");
            }

            cl_context_properties ctxProps = new cl_context_properties();
            ctxProps.addProperty(CL_CONTEXT_PLATFORM, platform);

            ctx = clCreateContext(ctxProps, 1, new cl_device_id[] { device }, null, null, null);
            queue = clCreateCommandQueueWithProperties(ctx, device, new cl_queue_properties(), null);

            String source;
            try (InputStream is = OCLPoC.class.getResourceAsStream("/genscoop.cl");
                    BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                source = reader.lines().collect(java.util.stream.Collectors.joining("\n"));
            } catch (Exception e) {
                throw new OCLCheckerException("Cannot read ocl file", e);
            }

            program = clCreateProgramWithSource(ctx, 1, new String[] { source }, null, null);
            clBuildProgram(program, 0, null, null, null, null);

            genKernel = clCreateKernel(program, "generate_scoops", null);
            getKernel = clCreateKernel(program, "get_scoops", null);
            getKernel2 = clCreateKernel(program, "get_scoops2", null);

            long[] genGroupSize = new long[1];
            long[] getGroupSize = new long[1];
            clGetKernelWorkGroupInfo(genKernel, device, CL_KERNEL_WORK_GROUP_SIZE, 8,
                    Pointer.to(genGroupSize), null);
            clGetKernelWorkGroupInfo(getKernel, device, CL_KERNEL_WORK_GROUP_SIZE, 8,
                    Pointer.to(getGroupSize), null);

            MAX_GROUP_ITEMS = Math.min(genGroupSize[0], getGroupSize[0]);

            if (MAX_GROUP_ITEMS <= 0) {
                throw new OCLCheckerException(
                        "OpenCL init error. Invalid max group items: " + MAX_GROUP_ITEMS);
            }

            long maxItemsByComputeUnits = getComputeUnits(device) * MAX_GROUP_ITEMS;

            maxItems = Math.min(calculateMaxItemsByMem(device), maxItemsByComputeUnits);

            if (maxItems % MAX_GROUP_ITEMS != 0) {
                maxItems -= (maxItems % MAX_GROUP_ITEMS);
            }

            if (maxItems <= 0) {
                throw new OCLCheckerException(
                        "OpenCL init error. Invalid calculated max items: " + maxItems);
            }
            logger.info("OCL max items: {}", maxItems);
        } catch (CLException e) {
            if (logger.isInfoEnabled()) {
                logger.info("OpenCL exception: {}", e.getMessage(), e);
            }
            destroy();
            throw new OCLCheckerException("OpenCL exception", e);
        }
    }

    public static long getMaxItems() {
        init(); // Ensure OCL is initialized before use
        return maxItems;
    }

    /**
     * Validates a batch of blocks' Proof-of-Capacity using OpenCL for GPU
     * acceleration.
     * <p>
     * This method implements a retry mechanism with dynamic batch sizing. If a
     * CLException occurs
     * (often due to the GPU being overwhelmed), it reduces the number of hashes
     * processed per enqueue
     * and retries the operation. This makes the validation process more resilient
     * to different
     * GPU hardware and driver capabilities without requiring manual configuration.
     * <p>
     * All OpenCL memory objects are managed within a try-finally block to ensure
     * they are
     * released, preventing resource leaks.
     *
     * @param blocks       A map of blocks to their previous blocks, needed for
     *                     scoop number calculation.
     * @param pocVersion   The Proof-of-Capacity version to use for validation.
     * @param blockService The service used to calculate scoop numbers.
     * @throws OCLCheckerException      if a fatal, non-recoverable OpenCL error
     *                                  occurs.
     * @throws PreValidateFailException if a block fails the PoC validation.
     */
    public static void validatePoC(HashMap<Block, Block> blocks, int pocVersion, BlockService blockService) {
        init();

        final long startTime = System.nanoTime();

        try {
            byte[] scoopsOut = new byte[MiningPlot.SCOOP_SIZE * blocks.size()];

            long jobSize = blocks.size();
            if (jobSize % MAX_GROUP_ITEMS != 0) {
                jobSize += (MAX_GROUP_ITEMS - (jobSize % MAX_GROUP_ITEMS));
            }

            if (jobSize > maxItems) {
                throw new IllegalStateException("Attempted to validate too many blocks at once with OCL");
            }

            long[] ids = new long[blocks.size()];
            long[] nonces = new long[blocks.size()];
            int[] scoopNums = new int[blocks.size()];

            ByteBuffer buffer = ByteBuffer.allocate(16);
            int i = 0;
            for (Block block : blocks.keySet()) {
                buffer.order(ByteOrder.LITTLE_ENDIAN);
                buffer.putLong(block.getGeneratorId());
                buffer.putLong(block.getNonce());
                buffer.flip();
                buffer.order(ByteOrder.BIG_ENDIAN);
                ids[i] = buffer.getLong();
                nonces[i] = buffer.getLong();
                buffer.clear();
                scoopNums[i] = blockService.getScoopNum(block);
                i++;
            }

            int initialStepSize = dynamicHashesPerEnqueue.get();
            int currentStepSize = initialStepSize;
            boolean success = false;
            while (!success && currentStepSize >= 1) {
                // Retry loop for GPU computation with dynamic step size adjustment.
                final long oclStartTime = System.nanoTime();
                cl_mem idMem = null;
                cl_mem nonceMem = null;
                cl_mem bufferMem = null;
                cl_mem scoopNumMem = null;
                cl_mem scoopOutMem = null;

                try {
                    if (logger.isDebugEnabled()) {
                        logger.debug("Starting OCL PoC validation for {} blocks with step size {}...", blocks.size(),
                                currentStepSize);
                    }

                    synchronized (oclLock) {
                        if (ctx == null) {
                            throw new OCLCheckerException("OCL context no longer exists");
                        }

                        idMem = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR, 8L * blocks.size(),
                                Pointer.to(ids), null);
                        nonceMem = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                8L * blocks.size(), Pointer.to(nonces), null);
                        bufferMem = clCreateBuffer(ctx, CL_MEM_READ_WRITE,
                                (long) (MiningPlot.PLOT_SIZE + 16) * blocks.size(), null, null);
                        scoopNumMem = clCreateBuffer(ctx, CL_MEM_READ_ONLY | CL_MEM_COPY_HOST_PTR,
                                4L * blocks.size(), Pointer.to(scoopNums), null);
                        scoopOutMem = clCreateBuffer(ctx, CL_MEM_READ_WRITE,
                                (long) MiningPlot.SCOOP_SIZE * blocks.size(), null, null);

                        int[] totalSize = new int[] { blocks.size() };

                        clSetKernelArg(genKernel, 0, Sizeof.cl_mem, Pointer.to(idMem));
                        clSetKernelArg(genKernel, 1, Sizeof.cl_mem, Pointer.to(nonceMem));
                        clSetKernelArg(genKernel, 2, Sizeof.cl_mem, Pointer.to(bufferMem));
                        clSetKernelArg(genKernel, 5, Sizeof.cl_int, Pointer.to(totalSize));

                        int c = 0;
                        int[] cur = new int[1];
                        int[] st = new int[1];
                        while (c < 8192) {
                            cur[0] = c;
                            st[0] = (c + currentStepSize) > 8192 ? 8192 - c : currentStepSize;
                            clSetKernelArg(genKernel, 3, Sizeof.cl_int, Pointer.to(cur));
                            clSetKernelArg(genKernel, 4, Sizeof.cl_int, Pointer.to(st));
                            clEnqueueNDRangeKernel(queue, genKernel, 1, null, new long[] { jobSize },
                                    new long[] { MAX_GROUP_ITEMS }, 0, null, null);

                            c += st[0];
                        }

                        if (pocVersion == 2) {
                            clSetKernelArg(getKernel2, 0, Sizeof.cl_mem, Pointer.to(scoopNumMem));
                            clSetKernelArg(getKernel2, 1, Sizeof.cl_mem, Pointer.to(bufferMem));
                            clSetKernelArg(getKernel2, 2, Sizeof.cl_mem, Pointer.to(scoopOutMem));
                            clSetKernelArg(getKernel2, 3, Sizeof.cl_int, Pointer.to(totalSize));
                            clEnqueueNDRangeKernel(queue, getKernel2, 1, null, new long[] { jobSize },
                                    new long[] { MAX_GROUP_ITEMS }, 0, null, null);
                        } else {
                            clSetKernelArg(getKernel, 0, Sizeof.cl_mem, Pointer.to(scoopNumMem));
                            clSetKernelArg(getKernel, 1, Sizeof.cl_mem, Pointer.to(bufferMem));
                            clSetKernelArg(getKernel, 2, Sizeof.cl_mem, Pointer.to(scoopOutMem));
                            clSetKernelArg(getKernel, 3, Sizeof.cl_int, Pointer.to(totalSize));
                            clEnqueueNDRangeKernel(queue, getKernel, 1, null, new long[] { jobSize },
                                    new long[] { MAX_GROUP_ITEMS }, 0, null, null);
                        }

                        clEnqueueReadBuffer(queue, scoopOutMem, true, 0,
                                (long) MiningPlot.SCOOP_SIZE * blocks.size(), Pointer.to(scoopsOut), 0, null, null);
                    }
                    success = true; // If we reach here, the computation was successful.
                    final long oclEndTime = System.nanoTime();
                    logger.debug("Finished OCL computation for {} blocks with step size {}. GPU time: {} ms.",
                            blocks.size(), currentStepSize, (oclEndTime - oclStartTime) / 1_000_000.0);
                } catch (CLException e) {
                    logger.warn("GPU error occurred with step size {}. Halving and retrying. Error: {}",
                            currentStepSize, e.getMessage());
                    currentStepSize /= 2;
                    if (currentStepSize == 0) {
                        dynamicHashesPerEnqueue.set(1);
                        throw new OCLCheckerException(
                                "GPU computation failed even with minimal step size. Check GPU/drivers.", e);
                    }
                } finally {
                    // Ensure all OpenCL memory objects are released, even if an error occurs.
                    if (idMem != null)
                        clReleaseMemObject(idMem);
                    if (nonceMem != null)
                        clReleaseMemObject(nonceMem);
                    if (bufferMem != null)
                        clReleaseMemObject(bufferMem);
                    if (scoopNumMem != null)
                        clReleaseMemObject(scoopNumMem);
                    if (scoopOutMem != null)
                        clReleaseMemObject(scoopOutMem);
                }
            }

            // Auto-tuning logic after the loop
            if (success) {
                if (currentStepSize < initialStepSize) {
                    // We had to decrease the step size to succeed. Let's save this new, safer
                    // value.
                    dynamicHashesPerEnqueue.set(currentStepSize);
                    logger.info("GPU batch size dynamically adjusted down to {} for stability.", currentStepSize);
                } else {
                    // Succeeded with the initial step size. Let's try to increase it for next time.
                    // Increase by 12.5% (i.e., divide by 8), but ensure it's at least 1.
                    int increase = Math.max(1, currentStepSize / 8);
                    int nextStepSize = Math.min(maxConfiguredHashesPerEnqueue, currentStepSize + increase);
                    if (dynamicHashesPerEnqueue.compareAndSet(currentStepSize, nextStepSize)
                            && nextStepSize > currentStepSize) {
                        logger.info("GPU batch size dynamically adjusted up to {} for performance.", nextStepSize);
                    }
                }
            }

            ByteBuffer scoopsBuffer = ByteBuffer.wrap(scoopsOut);
            byte[] scoop = new byte[MiningPlot.SCOOP_SIZE];

            blocks.keySet().forEach(block -> {
                try {
                    scoopsBuffer.get(scoop);
                    blockService.preVerify(block, blocks.get(block), scoop);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (BlockchainProcessor.BlockNotAcceptedException e) {
                    throw new PreValidateFailException("Block failed to prevalidate", e, block);
                }
            });
            final long endTime = System.nanoTime();
            if (logger.isDebugEnabled()) {
                logger.debug("Finished full PoC validation for {} blocks. Total time: {} ms.", blocks.size(),
                        (endTime - startTime) / 1_000_000.0);
            }
        } catch (CLException e) {
            // intentionally leave out of unverified cache. It won't slow it that much on
            // one failure and
            // avoids infinite looping on repeat failed attempts.
            throw new OCLCheckerException("OpenCL error", e);
        }
    }

    static void destroy() {
        synchronized (oclLock) {
            if (program != null) {
                clReleaseProgram(program);
                program = null;
            }
            if (genKernel != null) {
                clReleaseKernel(genKernel);
                genKernel = null;
            }
            if (getKernel != null) {
                clReleaseKernel(getKernel);
                getKernel = null;
            }
            if (getKernel2 != null) {
                clReleaseKernel(getKernel2);
                getKernel2 = null;
            }
            if (queue != null) {
                try {
                    clFinish(queue);
                } catch (CLException e) {
                    logger.warn("Failed to finish OCL command queue before destroying", e);
                }
                clReleaseCommandQueue(queue);
                queue = null;
            }
            if (ctx != null) {
                clReleaseContext(ctx);
                ctx = null;
            }
        }
    }

    private static boolean checkAvailable(cl_device_id device) {
        long[] available = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_AVAILABLE, Sizeof.cl_long, Pointer.to(available), null);
        return available[0] == 1;
    }

    // idk if the kernel works on big endian, but I'm guessing not and I don't have
    // the hardware to
    // find out
    private static boolean checkLittleEndian(cl_device_id device) {
        long[] endianLittle = new long[1];
        clGetDeviceInfo(device, CL_DEVICE_ENDIAN_LITTLE, Sizeof.cl_long, Pointer.to(endianLittle),
                null);
        return endianLittle[0] == 1;
    }

    private static int getComputeUnits(cl_device_id device) {
        int[] maxComputeUnits = new int[1];
        clGetDeviceInfo(device, CL_DEVICE_MAX_COMPUTE_UNITS, 4, Pointer.to(maxComputeUnits), null);
        return maxComputeUnits[0];
    }

    private static long calculateMaxItemsByMem(cl_device_id device) {
        long[] globalMemSize = new long[1];
        long[] maxMemAllocSize = new long[1];

        clGetDeviceInfo(device, CL_DEVICE_GLOBAL_MEM_SIZE, 8, Pointer.to(globalMemSize), null);
        clGetDeviceInfo(device, CL_DEVICE_MAX_MEM_ALLOC_SIZE, 8, Pointer.to(maxMemAllocSize), null);

        long maxItemsByGlobalMemSize = (globalMemSize[0] * MEM_PERCENT / 100) / MEM_PER_ITEM;
        long maxItemsByMaxAllocSize = (maxMemAllocSize[0] * MEM_PERCENT / 100) / BUFFER_PER_ITEM;

        logger.debug("Global Memory: {}", globalMemSize[0]);
        logger.debug("Max alloc Memory: {}", maxMemAllocSize[0]);
        logger.debug("maxItemsByGlobalMemSize: {}", maxItemsByGlobalMemSize);
        logger.debug("maxItemsByMaxAllocSize: {}", maxItemsByMaxAllocSize);

        return Math.min(maxItemsByGlobalMemSize, maxItemsByMaxAllocSize);
    }

    private static AutoChooseResult autoChooseDevice() {
        int[] numPlatforms = new int[1];
        clGetPlatformIDs(0, null, numPlatforms);

        if (numPlatforms[0] == 0) {
            throw new OCLCheckerException("No OpenCL platforms found");
        }

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms[0]];
        clGetPlatformIDs(platforms.length, platforms, null);

        AutoChooseResult bestResult = null;
        long bestScore = 0;
        boolean intel = false;
        for (int pfi = 0; pfi < platforms.length; pfi++) {
            long[] platformNameSize = new long[1];
            clGetPlatformInfo(platforms[pfi], CL_PLATFORM_NAME, 0, null, platformNameSize);
            byte[] platformNameChars = new byte[(int) platformNameSize[0]];
            clGetPlatformInfo(platforms[pfi], CL_PLATFORM_NAME, platformNameChars.length,
                    Pointer.to(platformNameChars), null);
            String platformName = new String(platformNameChars);

            logger.info("Platform {}: {}", pfi, platformName);

            int[] numDevices = new int[1];
            clGetDeviceIDs(platforms[pfi], CL_DEVICE_TYPE_GPU, 0, null, numDevices);

            if (numDevices[0] == 0) {
                continue;
            }

            cl_device_id[] devices = new cl_device_id[numDevices[0]];
            clGetDeviceIDs(platforms[pfi], CL_DEVICE_TYPE_GPU, devices.length, devices, null);

            for (int dvi = 0; dvi < devices.length; dvi++) {
                if (!checkAvailable(devices[dvi])) {
                    continue;
                }

                if (!checkLittleEndian(devices[dvi])) {
                    continue;
                }

                if (bestResult != null && platformName.toLowerCase(Locale.ENGLISH).contains("intel")) {
                    continue;
                }

                long[] clock = new long[1];
                clGetDeviceInfo(devices[dvi], CL_DEVICE_MAX_CLOCK_FREQUENCY, Sizeof.cl_long,
                        Pointer.to(clock), null);

                long maxItemsAtOnce = Math.min(calculateMaxItemsByMem(devices[dvi]),
                        (long) getComputeUnits(devices[dvi]) * 256);

                long score = maxItemsAtOnce * clock[0];

                if (bestResult == null || score > bestScore || intel) {
                    bestResult = new AutoChooseResult(pfi, dvi);
                    bestScore = score;
                    if (platformName.toLowerCase(Locale.ENGLISH).contains("intel")) {
                        intel = true;
                    }
                }
            }
        }

        return bestResult;
    }

    private static class AutoChooseResult {
        int platform;
        int device;

        AutoChooseResult(int platform, int device) {
            this.platform = platform;
            this.device = device;
        }

        int getPlatform() {
            return platform;
        }

        int getDevice() {
            return device;
        }
    }

    public static class OCLCheckerException extends RuntimeException {
        OCLCheckerException(String message) {
            super(message);
        }

        OCLCheckerException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class PreValidateFailException extends RuntimeException {
        transient Block block;

        PreValidateFailException(String message, Throwable cause, Block block) {
            super(message, cause);
            this.block = block;
        }

        public Block getBlock() {
            return block;
        }
    }
}
