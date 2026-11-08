package pl.automine;

import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.Set;

/**
 * AutoMine - mod KLIENTA (dziala na zwyklych serwerach, nie trzeba nic instalowac na serwerze).
 *
 * Rozdzka: drewniana siekiera
 *   LPM na blok = pozycja 1, PPM na blok = pozycja 2
 * Komendy: /kop pos1, /kop pos2 (blok pod toba), /kop start, /kop stop
 *
 * Kopie narzedziem z reki, blok po bloku (normalne lamanie, jak gdybys trzymal LPM).
 * Kopie tylko bloki w zasiegu reki - jesli obszar jest wiekszy, podchodz do niego.
 * Przy REPAIR_AT_DURABILITY wytrzymalosci wpisuje /repairall.
 * Przy MIN_DURABILITY_LEFT (gdy naprawa sie nie udala) zatrzymuje kopanie.
 */
public class AutoMineClient implements ClientModInitializer {

    public static final int MIN_DURABILITY_LEFT = 100;
    public static final int REPAIR_AT_DURABILITY = 150;
    public static final String REPAIR_COMMAND = "repairall";
    /** Odstep miedzy probami naprawy (ticki, 20 = 1 s). */
    public static final int REPAIR_COOLDOWN_TICKS = 100;
    /** Tyle ticków czekamy po wpisaniu komendy, zeby serwer odswiezyl narzedzie. */
    public static final int REPAIR_WAIT_TICKS = 10;
    /** Zasieg kopania liczony od oczu do srodka bloku. */
    public static final double REACH = 4.0;
    /** Jesli blok nie zniknie w tym czasie, jest pomijany (np. zle narzedzie). */
    public static final int MAX_TICKS_PER_BLOCK = 200;
    /** Maksymalna liczba blokow w zaznaczeniu. */
    public static final long MAX_VOLUME = 500_000L;

    private static BlockPos pos1, pos2;
    private static boolean running = false;
    private static int minX, minY, minZ, maxX, maxY, maxZ;
    private static final Set<BlockPos> skipped = new HashSet<>();
    private static BlockPos target;
    private static int targetTicks;
    private static long tickCounter = 0;
    private static long lastRepairTick = -1000;
    private static long pauseUntil = 0;
    private static long lastHintTick = -1000;

    @Override
    public void onInitializeClient() {
        // Rozdzka: LPM = pozycja 1 (FAIL = nic nie wysylamy do serwera)
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (!world.isClient || hand != Hand.MAIN_HAND
                    || !player.getMainHandStack().isOf(Items.WOODEN_AXE)) {
                return ActionResult.PASS;
            }
            pos1 = pos.toImmutable();
            msg(player, "Pozycja 1: " + fmt(pos));
            return ActionResult.FAIL;
        });

        // Rozdzka: PPM = pozycja 2
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient || hand != Hand.MAIN_HAND
                    || !player.getMainHandStack().isOf(Items.WOODEN_AXE)) {
                return ActionResult.PASS;
            }
            pos2 = hit.getBlockPos().toImmutable();
            msg(player, "Pozycja 2: " + fmt(hit.getBlockPos()));
            return ActionResult.FAIL;
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommandManager.literal("kop")
                .then(ClientCommandManager.literal("pos1").executes(ctx -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    if (p == null) return 0;
                    pos1 = p.getBlockPos().down();
                    msg(p, "Pozycja 1: " + fmt(pos1));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("pos2").executes(ctx -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    if (p == null) return 0;
                    pos2 = p.getBlockPos().down();
                    msg(p, "Pozycja 2: " + fmt(pos2));
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("start").executes(ctx -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    if (p == null) return 0;
                    start(p);
                    return Command.SINGLE_SUCCESS;
                }))
                .then(ClientCommandManager.literal("stop").executes(ctx -> {
                    ClientPlayerEntity p = MinecraftClient.getInstance().player;
                    if (p == null) return 0;
                    if (running) {
                        stop(p, "Stop - kopanie zatrzymane.");
                    } else {
                        msg(p, "Kopanie nie jest uruchomione.");
                    }
                    return Command.SINGLE_SUCCESS;
                }))
            ));

        ClientTickEvents.END_CLIENT_TICK.register(AutoMineClient::tick);
    }

    private static void start(ClientPlayerEntity player) {
        if (pos1 == null || pos2 == null) {
            msg(player, "Najpierw zaznacz obie pozycje (LPM / PPM drewnianą siekierą).");
            return;
        }
        ItemStack tool = player.getMainHandStack();
        if (!tool.isDamageable()) {
            msg(player, "Weź do ręki narzędzie (kilof, łopata, siekiera...).");
            return;
        }
        int left = tool.getMaxDamage() - tool.getDamage();
        if (left <= MIN_DURABILITY_LEFT) {
            msg(player, "Narzędzie ma za mało wytrzymałości (zostało " + left + ").");
            return;
        }
        minX = Math.min(pos1.getX(), pos2.getX());
        maxX = Math.max(pos1.getX(), pos2.getX());
        minY = Math.min(pos1.getY(), pos2.getY());
        maxY = Math.max(pos1.getY(), pos2.getY());
        minZ = Math.min(pos1.getZ(), pos2.getZ());
        maxZ = Math.max(pos1.getZ(), pos2.getZ());
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_VOLUME) {
            msg(player, "Obszar jest za duży (" + volume + " bloków, max " + MAX_VOLUME + ").");
            return;
        }
        skipped.clear();
        target = null;
        targetTicks = 0;
        lastRepairTick = -1000;
        pauseUntil = 0;
        lastHintTick = -1000;
        running = true;
        msg(player, "Start kopania (" + volume + " bloków).");
    }

    private static void stop(ClientPlayerEntity player, String reason) {
        running = false;
        target = null;
        if (player != null) msg(player, reason);
    }

    private static void tick(MinecraftClient client) {
        tickCounter++;
        if (!running) return;

        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        ClientPlayerInteractionManager im = client.interactionManager;
        if (player == null || world == null || im == null) {
            running = false;
            target = null;
            return;
        }
        if (tickCounter < pauseUntil) return;

        ItemStack tool = player.getMainHandStack();
        if (!tool.isDamageable()) {
            stop(player, "Stop - brak narzędzia w ręce.");
            return;
        }
        int left = tool.getMaxDamage() - tool.getDamage();

        // Narzedzie na wyczerpaniu -> /repairall
        if (left <= REPAIR_AT_DURABILITY && tickCounter - lastRepairTick >= REPAIR_COOLDOWN_TICKS) {
            lastRepairTick = tickCounter;
            player.networkHandler.sendChatCommand(REPAIR_COMMAND);
            pauseUntil = tickCounter + REPAIR_WAIT_TICKS;
            target = null;
            return;
        }
        // Naprawa sie nie udala i narzedzie jest prawie zepsute -> stop
        if (left <= MIN_DURABILITY_LEFT) {
            stop(player, "Stop - narzędziu zostało " + left + " wytrzymałości.");
            return;
        }

        Vec3d eye = player.getEyePos();

        // Sprawdz aktualny cel
        if (target != null) {
            BlockState st = world.getBlockState(target);
            boolean outOfReach = Vec3d.ofCenter(target).squaredDistanceTo(eye) > REACH * REACH;
            if (st.isAir()) {
                target = null;
            } else if (++targetTicks > MAX_TICKS_PER_BLOCK) {
                skipped.add(target);
                target = null;
            } else if (outOfReach) {
                target = null;
            }
        }

        // Wybierz nowy cel
        if (target == null) {
            target = findTarget(world, eye);
            targetTicks = 0;
            if (target == null) {
                if (tickCounter % 40 == 0 && !anyLeft(world)) {
                    stop(player, "Gotowe - cały obszar wykopany.");
                } else if (tickCounter - lastHintTick >= 200) {
                    lastHintTick = tickCounter;
                    msg(player, "Brak bloków w zasięgu - podejdź bliżej zaznaczonego obszaru.");
                }
                return;
            }
        }

        // Kop (to samo co trzymanie LPM na bloku)
        Vec3d diff = eye.subtract(Vec3d.ofCenter(target));
        Direction dir = Direction.getFacing(diff.x, diff.y, diff.z);
        im.updateBlockBreakingProgress(target, dir);
        player.swingHand(Hand.MAIN_HAND);
    }

    /** Najwyzszy blok w zasiegu (a przy rownej wysokosci - najblizszy). */
    private static BlockPos findTarget(ClientWorld world, Vec3d eye) {
        int r = (int) Math.ceil(REACH);
        int cx = (int) Math.floor(eye.x);
        int cy = (int) Math.floor(eye.y);
        int cz = (int) Math.floor(eye.z);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int y = Math.min(maxY, cy + r); y >= Math.max(minY, cy - r); y--) {
            for (int x = Math.max(minX, cx - r); x <= Math.min(maxX, cx + r); x++) {
                for (int z = Math.max(minZ, cz - r); z <= Math.min(maxZ, cz + r); z++) {
                    m.set(x, y, z);
                    double d = Vec3d.ofCenter(m).squaredDistanceTo(eye);
                    if (d > REACH * REACH) continue;
                    if (!mineable(world, m)) continue;
                    if (d < bestDist) {
                        bestDist = d;
                        best = m.toImmutable();
                    }
                }
            }
            if (best != null) return best; // zaczynamy od gory, wiec pierwsza warstwa z blokiem wygrywa
        }
        return best;
    }

    private static boolean anyLeft(ClientWorld world) {
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int y = maxY; y >= minY; y--) {
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    m.set(x, y, z);
                    if (mineable(world, m)) return true;
                }
            }
        }
        return false;
    }

    private static boolean mineable(ClientWorld world, BlockPos pos) {
        BlockState st = world.getBlockState(pos);
        if (st.isAir()) return false;
        if (st.getBlock() instanceof FluidBlock) return false;
        if (st.getHardness(world, pos) < 0) return false; // bedrock itp.
        return !skipped.contains(pos);
    }

    private static void msg(net.minecraft.entity.player.PlayerEntity player, String text) {
        player.sendMessage(Text.literal("[AutoMine] " + text), false);
    }

    private static String fmt(BlockPos p) {
        return p.getX() + ", " + p.getY() + ", " + p.getZ();
    }
}
