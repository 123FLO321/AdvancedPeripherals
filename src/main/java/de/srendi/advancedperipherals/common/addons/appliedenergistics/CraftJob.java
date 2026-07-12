package de.srendi.advancedperipherals.common.addons.appliedenergistics;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import de.srendi.advancedperipherals.AdvancedPeripherals;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class CraftJob implements ILuaCallback {

    public static final String EVENT = "crafting";

    public final UUID id;

    private final IComputerAccess computer;
    private final IGridNode node;
    private final IActionSource source;
    private final ICraftingSimulationRequester requester;
    private final ICraftingCPU target;
    private final AEKey item;

    private final long amount;
    private final boolean simulate;
    private final Level world;
    private Future<ICraftingPlan> futureJob;
    @Nullable private volatile ICraftingCPU usedCPU;
    private volatile boolean startedCrafting = false;
    private volatile boolean finishedCrafting = false;
    private volatile boolean cancelledCrafting = false;

    private long createdAtGameTime = -1;
    private long disposedAtGameTime = -1;

    private MethodResult result;
    private LuaException exception;

    public CraftJob(UUID id, Level world, final IComputerAccess computer, IGridNode node, AEKey item, long amount, boolean simulate,
                    IActionSource source, ICraftingSimulationRequester requester, ICraftingCPU target) {
        this.id = id;
        this.computer = computer;
        this.node = node;
        this.world = world;
        this.source = source;
        this.item = item;
        this.amount = amount;
        this.simulate = simulate;
        this.requester = requester;
        this.target = target;
    }

    protected void fireEvent(boolean success, @Nullable String exception) {
        this.result = MethodResult.of(success, exception);
        this.exception = new LuaException(exception);
        this.computer.queueEvent(EVENT, success, exception, id.toString());
    }

    protected void fireNotConnected() {
        fireEvent(false, "not connected");
    }

    public boolean startCrafting() {
        IGrid grid = node.getGrid();
        if (grid == null) { //true when the block is not connected
            fireNotConnected();
            return false;
        }

        ICraftingService crafting = grid.getService(ICraftingService.class);

        if (item == null) {
            AdvancedPeripherals.debug("Could not get AEItem from monitor", org.apache.logging.log4j.Level.FATAL);
            return false;
        }

        if (!crafting.isCraftable(item)) {
            fireEvent(false, item.getId().toString() + " is not craftable");
            return false;
        }

        futureJob = crafting.beginCraftingCalculation(world, this.requester, item, amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        fireEvent(true, "Started calculation of the recipe. After it's finished, the system will start crafting the item.");
        return true;
    }

    public void maybeCraft() {
        if (!this.simulate) {
            attemptCraft();
        }
    }

    public void checkFinished() {
        if (startedCrafting && !finishedCrafting && usedCPU != null && !usedCPU.isBusy()) {
            finishedCrafting = true;
        }
    }

    public boolean attemptCraft() {
        if (startedCrafting || cancelledCrafting || futureJob == null || !futureJob.isDone()) {
            return false;
        }

        ICraftingPlan job;
        try {
            job = futureJob.get();
        } catch (ExecutionException | InterruptedException | CancellationException ex) {
            AdvancedPeripherals.debug("Tried to get job, but job calculation is not done. Should be done.", org.apache.logging.log4j.Level.FATAL);
            ex.printStackTrace();
            return false;
        }

        if (job == null) {
            AdvancedPeripherals.debug("Job is null, should not be null.", org.apache.logging.log4j.Level.FATAL);
            return false;
        }

        if (job.simulation()) {
            return false;
        }

        IGrid grid = node.getGrid();
        if (grid == null) {
            return false;
        }

        ICraftingService crafting = grid.getService(ICraftingService.class);
        List<ICraftingCPU> idleCpus = crafting.getCpus().stream().filter(cpu -> !cpu.isBusy()).toList();
        ICraftingSubmitResult result = crafting.submitJob(job, null, target, false, this.source);
        if (!result.successful()) {
            fireEvent(false, "Could not start crafting: " + result.errorCode());
            return false;
        } else {
            idleCpus.stream().filter(cpu -> {
                CraftingJobStatus status = cpu.getJobStatus();
                if (status == null) {
                    return false;
                }
                return status.crafting().equals(job.finalOutput());
            }).findFirst().ifPresent(matchedCPU -> {
                usedCPU = matchedCPU;
            });
        }

        startedCrafting = true;

        return true;
    }

    public boolean attemptCancel() {
        if (cancelledCrafting || finishedCrafting) {
            return false;
        }
        Future<ICraftingPlan> future = futureJob;
        if (future != null && !future.isDone()) {
            // Calculation still pending: cancel it without ever blocking (a mainThread
            // caller waiting on get() would deadlock the tick that drives the calculation).
            cancelledCrafting = true;
            future.cancel(true);
            return true;
        }
        ICraftingPlan job = getJob();
        if (job != null && usedCPU != null) {
            CraftingJobStatus status = usedCPU.getJobStatus();
            if (status != null && status.crafting().equals(job.finalOutput())) {
                cancelledCrafting = true;
                usedCPU.cancelJob();
                return true;
            }
        }
        if (future != null) {
            future.cancel(true);
            cancelledCrafting = true;
            return true;
        }
        return false;
    }

    @NotNull
    @Override
    public MethodResult resume(Object[] objects) {
        if (result != null) return result;
        if (exception != null) return MethodResult.of(exception);
        return MethodResult.of();
    }

    @Nullable
    private ICraftingPlan getJob() {
        if (futureJob == null) {
            return null;
        }
        try {
            return futureJob.get();
        } catch (Exception e) {
            return null;
        }
    }

    @Nullable CraftingJobStatus getStatus() {
        if (usedCPU == null) {
            return null;
        }
        return usedCPU.getJobStatus();
    }

    @Nullable
    public ICraftingCPU getUsedCPU() {
        return usedCPU;
    }

    public boolean isActive() {
        return startedCrafting && !finishedCrafting && !cancelledCrafting;
    }

    public boolean isActivelyUsing(ICraftingCPU cpu) {
        if (!isActive() || usedCPU != cpu) {
            return false;
        }
        CraftingJobStatus status = cpu.getJobStatus();
        ICraftingPlan job = getJob();
        return status != null && job != null && status.crafting().equals(job.finalOutput());
    }

    public void stampCreated(long gameTime) {
        if (createdAtGameTime == -1) {
            createdAtGameTime = gameTime;
        }
    }

    public long getCreatedGameTime() {
        return createdAtGameTime;
    }

    public void stampDisposed(long gameTime) {
        if (disposedAtGameTime == -1) {
            disposedAtGameTime = gameTime;
        }
    }

    public boolean isDisposeStamped() {
        return disposedAtGameTime != -1;
    }

    public long getDisposedGameTime() {
        return disposedAtGameTime;
    }

    @LuaFunction
    public MethodResult getId() {
        return MethodResult.of(id.toString());
    }

    @LuaFunction
    public MethodResult start() {
        boolean started = attemptCraft();
        return MethodResult.of(started);
    }

    @LuaFunction
    public MethodResult isStarted() {
        return MethodResult.of(startedCrafting);
    }

    @LuaFunction
    public MethodResult isReady() {
        if (futureJob == null) {
            return MethodResult.of(false);
        }
        return MethodResult.of(futureJob.isDone());
    }

    @LuaFunction
    public MethodResult isCanceled() {
        return MethodResult.of(cancelledCrafting);
    }

    @LuaFunction
    public MethodResult isDone() {
        return MethodResult.of(startedCrafting && finishedCrafting);
    }

    @LuaFunction
    public MethodResult cancel() {
        boolean canceled = attemptCancel();
        return MethodResult.of(canceled);
    }

    public boolean canDispose() {
        return finishedCrafting || cancelledCrafting;
    }

    @LuaFunction
    public MethodResult getFinalOutput() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of();
        }
        return MethodResult.of(AppEngApi.getObjectFromGenericStack(job.finalOutput(), 1L));
    }

    @LuaFunction
    public MethodResult getBytes() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of(0);
        }
        return MethodResult.of(job.bytes());
    }

    @LuaFunction
    public MethodResult getUsedItems() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of();
        }
        return MethodResult.of(AppEngApi.getObjectFromKeyCounter(job.usedItems()));
    }

    @LuaFunction
    public MethodResult getEmittedItems() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of();
        }
        return MethodResult.of(AppEngApi.getObjectFromKeyCounter(job.emittedItems()));
    }

    @LuaFunction
    public MethodResult getMissingItems() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of();
        }
        return MethodResult.of(AppEngApi.getObjectFromKeyCounter(job.missingItems()));
    }

    @LuaFunction
    public MethodResult getPatternTimes() {
        ICraftingPlan job = getJob();
        if (job == null) {
            return MethodResult.of();
        }
        return MethodResult.of(AppEngApi.getObjectFromPatternTimes(job.patternTimes()));
    }

    @LuaFunction
    public MethodResult getProgress() {
        if (!startedCrafting) {
            return MethodResult.of(0.0);
        }
        if (finishedCrafting) {
            return MethodResult.of(1.0);
        }
        CraftingJobStatus status = getStatus();
        if (status == null) {
            return MethodResult.of(0.0);
        } else {
            float total = status.totalItems();
            float progress = status.progress();
            if (total == 0) {
                return MethodResult.of(0.0);
            } else {
                return MethodResult.of(progress / total);
            }
        }
    }

    @LuaFunction
    public MethodResult getElapsedTime() {
        CraftingJobStatus status = getStatus();
        if (status == null) {
            return MethodResult.of();
        } else {
            return MethodResult.of(status.elapsedTimeNanos());
        }
    }

    @LuaFunction
    public MethodResult getCpuName() {
        if (usedCPU == null) {
            return MethodResult.of();
        } else {
            return MethodResult.of(usedCPU.getName() != null ? usedCPU.getName().getString() : "Unnamed");
        }
    }

    @LuaFunction(mainThread = true)
    public MethodResult getStatusItems() {
        ICraftingCPU cpu = usedCPU;
        if (cpu == null || !isActivelyUsing(cpu) || !(cpu instanceof CraftingCPUCluster cluster)) {
            return MethodResult.of(List.of());
        }
        CraftingCpuLogic logic = cluster.craftingLogic;
        KeyCounter allItems = new KeyCounter();
        logic.getAllItems(allItems);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Object2LongMap.Entry<AEKey> entry : allItems) {
            AEKey key = entry.getKey();
            entries.add(AppEngApi.getObjectFromStatusItem(key, logic.getStored(key), logic.getWaitingFor(key), logic.getPendingOutputs(key)));
        }
        return MethodResult.of(entries);
    }
}
