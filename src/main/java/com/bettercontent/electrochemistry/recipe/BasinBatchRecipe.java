package com.bettercontent.electrochemistry.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Two strict, disjoint contracts under one recipe type. V1 remains the historical
 * one-item test fixture; V2 represents a consumed anode + consumed seed with an
 * exactly returned aqueous bath. V2 has no gas, electrode wear or fluid output.
 * JSON alone never bypasses the Basin's transactional reservation checks.
 */
public final class BasinBatchRecipe implements Recipe<Container> {
    private static final Set<String> V1_FIELDS = Set.of("type", "ingredient", "result", "min_voltage", "work_joules");
    private static final Set<String> V2_FIELDS = Set.of("type", "consumed_anode", "consumed_seed",
            "bath_input", "bath_return", "result", "min_voltage", "work_joules");
    private static final Set<String> STACK_FIELDS = Set.of("item", "count");
    private static final Set<String> BATH_INPUT_FIELDS = Set.of("fluid", "amount", "consume_mb");
    private static final Set<String> BATH_RETURN_FIELDS = Set.of("fluid", "amount");
    private static final ResourceLocation AQUEOUS_COPPER_SULFATE = new ResourceLocation(
            "better_electrochemistry", "aqueous_copper_ii_sulfate");
    // Old v1 packets begin with an item ResourceLocation; this deliberately
    // unregistered item ID frames v2 without changing a single v1 network byte.
    private static final ResourceLocation V2_NETWORK_MARKER = new ResourceLocation(
            "better_electrochemistry", "__electroforming_v2_network_marker");

    private final ResourceLocation id;
    private final boolean electroforming;
    private final Item ingredientItem;
    private final int ingredientCount;
    private final Item anodeItem;
    private final int anodeCount;
    private final Item cathodeSeedItem;
    private final int cathodeSeedCount;
    private final Fluid bathFluid;
    private final int bathAmountMb;
    private final ItemStack output;
    private final float minVoltage;
    private final double workJoules;
    private final NonNullList<Ingredient> ingredients;
    private final String fingerprint;

    /** Keep the v1 fixture constructor and its exact one-item semantics intact. */
    public BasinBatchRecipe(ResourceLocation id, Item ingredientItem, int ingredientCount,
                            ItemStack output, float minVoltage, double workJoules) {
        validateResultAndPower(id, output, minVoltage, workJoules);
        if (ingredientItem == null || ingredientItem == Items.AIR
                || new ItemStack(ingredientItem).isDamageableItem()
                || ingredientCount < 1 || ingredientCount > ingredientItem.getMaxStackSize())
            throw new IllegalArgumentException("Unsupported v1 Basin ingredient " + id);
        if (V2_NETWORK_MARKER.equals(itemId(ingredientItem)))
            throw new IllegalArgumentException("Reserved v2 network marker cannot be a v1 ingredient: " + id);
        this.id = id;
        this.electroforming = false;
        this.ingredientItem = ingredientItem;
        this.ingredientCount = ingredientCount;
        this.anodeItem = null;
        this.anodeCount = 0;
        this.cathodeSeedItem = null;
        this.cathodeSeedCount = 0;
        this.bathFluid = Fluids.EMPTY;
        this.bathAmountMb = 0;
        this.output = output.copy();
        this.minVoltage = minVoltage;
        this.workJoules = workJoules;
        this.ingredients = NonNullList.of(Ingredient.EMPTY, Ingredient.of(ingredientItem));
        this.fingerprint = hash("basin_batch:v1|" + id + "|" + itemId(ingredientItem) + "|" + ingredientCount
                + "|" + itemId(output.getItem()) + "|" + output.getCount() + "|"
                + Float.toHexString(minVoltage) + "|" + Double.toHexString(workJoules));
    }

    /** V2's bath is *reserved and returned*, never consumed or output as product. */
    public static BasinBatchRecipe electroforming(ResourceLocation id, Item anode, int anodeCount,
            Item seed, int seedCount, Fluid bath, int bathAmountMb, ItemStack output,
            float minVoltage, double workJoules) {
        return new BasinBatchRecipe(id, anode, anodeCount, seed, seedCount, bath,
                bathAmountMb, output, minVoltage, workJoules);
    }

    private BasinBatchRecipe(ResourceLocation id, Item anode, int anodeCount, Item seed, int seedCount,
            Fluid bath, int bathAmountMb, ItemStack output, float minVoltage, double workJoules) {
        validateResultAndPower(id, output, minVoltage, workJoules);
        if (anode == null || anode == Items.AIR || seed == null || seed == Items.AIR || anode == seed
                || new ItemStack(anode).isDamageableItem() || new ItemStack(seed).isDamageableItem()
                || anodeCount < 1 || anodeCount > anode.getMaxStackSize()
                || seedCount < 1 || seedCount > seed.getMaxStackSize()
                || bath == null || bath == Fluids.EMPTY || bathAmountMb != 250
                || !AQUEOUS_COPPER_SULFATE.equals(ForgeRegistries.FLUIDS.getKey(bath)))
            throw new IllegalArgumentException("Unsupported electroforming Basin recipe " + id);
        // First playable electroforming route: prevent a data-only recipe from
        // silently making a second material, changing mass balance or inventing
        // an otherwise unrecoverable electrolyte. Gameplay thresholds remain data.
        var sheet = ForgeRegistries.ITEMS.getValue(new ResourceLocation("create", "copper_sheet"));
        if (anode != Items.COPPER_INGOT || sheet == null || sheet == Items.AIR
                || seed != sheet || seedCount != 1 || anodeCount != 1
                || !output.is(sheet) || output.getCount() != 2)
            throw new IllegalArgumentException("Unreviewed electroforming mass balance " + id);
        this.id = id;
        this.electroforming = true;
        this.ingredientItem = null;
        this.ingredientCount = 0;
        this.anodeItem = anode;
        this.anodeCount = anodeCount;
        this.cathodeSeedItem = seed;
        this.cathodeSeedCount = seedCount;
        this.bathFluid = bath;
        this.bathAmountMb = bathAmountMb;
        this.output = output.copy();
        this.minVoltage = minVoltage;
        this.workJoules = workJoules;
        this.ingredients = NonNullList.of(Ingredient.EMPTY, Ingredient.of(anode), Ingredient.of(seed));
        this.fingerprint = hash("basin_batch:v2|" + id + "|anode=" + itemId(anode) + "|" + anodeCount
                + "|seed=" + itemId(seed) + "|" + seedCount + "|bath_in=" + AQUEOUS_COPPER_SULFATE
                + "|" + bathAmountMb + "|consume=0|bath_return=" + AQUEOUS_COPPER_SULFATE
                + "|" + bathAmountMb + "|result=" + itemId(output.getItem()) + "|" + output.getCount()
                + "|" + Float.toHexString(minVoltage) + "|" + Double.toHexString(workJoules));
    }

    private static void validateResultAndPower(ResourceLocation id, ItemStack output, float volts, double work) {
        if (id == null || output == null || output.isEmpty() || output.hasTag() || output.isDamageableItem()
                || output.getCount() < 1 || output.getCount() > output.getMaxStackSize()
                || !Float.isFinite(volts) || volts <= 0 || !Double.isFinite(work) || work <= 0)
            throw new IllegalArgumentException("Unsupported or invalid Basin batch recipe " + id);
        itemId(output.getItem());
    }

    private static ResourceLocation itemId(Item item) {
        var id = ForgeRegistries.ITEMS.getKey(item);
        if (id == null) throw new IllegalArgumentException("Unregistered Basin item " + item);
        return id;
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Production v2 is enabled under the explicit best-effort physical-break
     * policy: failed fluid-entity spawns may lose the reserved bath. Ordinary
     * completion/refund still require an actual native bath return or canister.
     * This is an activation gate, NOT evidence of compilation or game acceptance.
     */
    public static boolean electroformingRuntimeEnabled() { return true; }
    public boolean isElectroforming() { return electroforming; }
    /** V1-only getters remain stable for the existing synthetic fixture. */
    public Item inputItem() { return ingredientItem; }
    public int inputCount() { return ingredientCount; }
    public Item anodeItem() { return anodeItem; }
    public int anodeCount() { return anodeCount; }
    public Item cathodeSeedItem() { return cathodeSeedItem; }
    public int cathodeSeedCount() { return cathodeSeedCount; }
    public Fluid bathFluid() { return bathFluid; }
    public int bathAmountMb() { return bathAmountMb; }
    public ItemStack output() { return output.copy(); }
    public float minVoltage() { return minVoltage; }
    public double workJoules() { return workJoules; }
    public String fingerprint() { return fingerprint; }
    public boolean matchesInput(ItemStack stack) {
        return !electroforming && !stack.isEmpty() && !stack.hasTag() && !stack.isDamageableItem()
                && stack.is(ingredientItem) && stack.getCount() >= ingredientCount;
    }

    @Override public boolean matches(Container inventory, Level level) { return false; }
    @Override public ItemStack assemble(Container inventory, RegistryAccess access) { return ItemStack.EMPTY; }
    @Override public boolean canCraftInDimensions(int width, int height) { return false; }
    @Override public ItemStack getResultItem(RegistryAccess access) { return output(); }
    @Override public NonNullList<Ingredient> getIngredients() { return ingredients; }
    @Override public ResourceLocation getId() { return id; }
    @Override public RecipeSerializer<?> getSerializer() { return ElectrochemistryRecipes.BASIN_BATCH_SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return ElectrochemistryRecipes.BASIN_BATCH.get(); }
    @Override public boolean isSpecial() { return true; }

    public static final class Serializer implements RecipeSerializer<BasinBatchRecipe> {
        private static void exactFields(JsonObject object, Set<String> allowed) {
            for (String key : object.keySet()) if (!allowed.contains(key))
                throw new JsonParseException("Unsupported Basin batch field: " + key);
        }

        private static Item item(JsonObject object) {
            var id = new ResourceLocation(GsonHelper.getAsString(object, "item"));
            var item = ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) throw new JsonParseException("Unknown item: " + id);
            return item;
        }

        private static Fluid fluid(JsonObject object) {
            var id = new ResourceLocation(GsonHelper.getAsString(object, "fluid"));
            var fluid = ForgeRegistries.FLUIDS.getValue(id);
            if (fluid == null || fluid == Fluids.EMPTY) throw new JsonParseException("Unknown fluid: " + id);
            return fluid;
        }

        @Override public BasinBatchRecipe fromJson(ResourceLocation id, JsonObject json) {
            var out = GsonHelper.getAsJsonObject(json, "result");
            exactFields(out, STACK_FIELDS);
            var result = new ItemStack(item(out), GsonHelper.getAsInt(out, "count"));
            float voltage = GsonHelper.getAsFloat(json, "min_voltage");
            double work = GsonHelper.getAsDouble(json, "work_joules");
            if (!json.has("consumed_anode")) {
                exactFields(json, V1_FIELDS);
                var in = GsonHelper.getAsJsonObject(json, "ingredient");
                exactFields(in, STACK_FIELDS);
                return new BasinBatchRecipe(id, item(in), GsonHelper.getAsInt(in, "count"),
                        result, voltage, work);
            }
            exactFields(json, V2_FIELDS);
            var anode = GsonHelper.getAsJsonObject(json, "consumed_anode");
            var seed = GsonHelper.getAsJsonObject(json, "consumed_seed");
            var bathInput = GsonHelper.getAsJsonObject(json, "bath_input");
            var bathReturn = GsonHelper.getAsJsonObject(json, "bath_return");
            exactFields(anode, STACK_FIELDS);
            exactFields(seed, STACK_FIELDS);
            exactFields(bathInput, BATH_INPUT_FIELDS);
            exactFields(bathReturn, BATH_RETURN_FIELDS);
            var fluid = fluid(bathInput);
            int amount = GsonHelper.getAsInt(bathInput, "amount");
            if (GsonHelper.getAsInt(bathInput, "consume_mb") != 0
                    || fluid(bathReturn) != fluid
                    || GsonHelper.getAsInt(bathReturn, "amount") != amount)
                throw new JsonParseException("Electroforming bath must be returned unchanged: " + id);
            return electroforming(id, item(anode), GsonHelper.getAsInt(anode, "count"),
                    item(seed), GsonHelper.getAsInt(seed, "count"), fluid, amount,
                    result, voltage, work);
        }

        @Override public BasinBatchRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            var first = buf.readResourceLocation();
            if (!V2_NETWORK_MARKER.equals(first)) {
                var input = ForgeRegistries.ITEMS.getValue(first);
                int count = buf.readVarInt();
                var result = buf.readItem();
                float voltage = buf.readFloat();
                double work = buf.readDouble();
                return new BasinBatchRecipe(id, input, count, result, voltage, work);
            }
            var anode = ForgeRegistries.ITEMS.getValue(buf.readResourceLocation());
            int anodeCount = buf.readVarInt();
            var seed = ForgeRegistries.ITEMS.getValue(buf.readResourceLocation());
            int seedCount = buf.readVarInt();
            var bath = ForgeRegistries.FLUIDS.getValue(buf.readResourceLocation());
            int amount = buf.readVarInt();
            var result = buf.readItem();
            float voltage = buf.readFloat();
            double work = buf.readDouble();
            return electroforming(id, anode, anodeCount, seed, seedCount, bath, amount,
                    result, voltage, work);
        }

        @Override public void toNetwork(FriendlyByteBuf buf, BasinBatchRecipe recipe) {
            if (!recipe.electroforming) {
                buf.writeResourceLocation(itemId(recipe.ingredientItem));
                buf.writeVarInt(recipe.ingredientCount);
            } else {
                buf.writeResourceLocation(V2_NETWORK_MARKER);
                buf.writeResourceLocation(itemId(recipe.anodeItem));
                buf.writeVarInt(recipe.anodeCount);
                buf.writeResourceLocation(itemId(recipe.cathodeSeedItem));
                buf.writeVarInt(recipe.cathodeSeedCount);
                buf.writeResourceLocation(ForgeRegistries.FLUIDS.getKey(recipe.bathFluid));
                buf.writeVarInt(recipe.bathAmountMb);
            }
            buf.writeItem(recipe.output);
            buf.writeFloat(recipe.minVoltage);
            buf.writeDouble(recipe.workJoules);
        }
    }
}
