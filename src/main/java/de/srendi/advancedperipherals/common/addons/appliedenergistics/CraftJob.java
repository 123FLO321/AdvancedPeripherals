package de.srendi.advancedperipherals.common.addons.appliedenergistics;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import dan200.computercraft.api.lua.ILuaCallback;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.lua.MethodResult;
import dan200.computercraft.api.peripheral.IComputerAccess;
import de.srendi.advancedperipherals.AdvancedPeripherals;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
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
    @Nullable private ICraftingCPU usedCPU;
    private boolean startedCrafting = false;
    private boolean finishedCrafting = false;
    private boolean cancelledCrafting = false;

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
        if (startedCrafting || futureJob == null || !futureJob.isDone()) {
            return false;
        }

        ICraftingPlan job;
        try {
            job = futureJob.get();
        } catch (ExecutionException | InterruptedException ex) {
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
        System.out.println(idleCpus.size());
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
        ICraftingPlan job = getJob();
        if (job != null && usedCPU != null) {
            CraftingJobStatus status = usedCPU.getJobStatus();
            if (status != null && status.crafting().equals(job.finalOutput())) {
                cancelledCrafting = true;
                usedCPU.cancelJob();
                return true;
            }
        }
        if (futureJob != null) {
            futureJob.cancel(true);
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
}
