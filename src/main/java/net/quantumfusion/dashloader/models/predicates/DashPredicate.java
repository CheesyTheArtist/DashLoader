package net.quantumfusion.dashloader.models.predicates;

import net.minecraft.block.BlockState;
import net.quantumfusion.dashloader.DashRegistry;
import net.quantumfusion.dashloader.util.Dashable;

import java.util.function.Predicate;

public interface DashPredicate extends Dashable {
    Predicate<BlockState> toUndash(DashRegistry registry);

}
