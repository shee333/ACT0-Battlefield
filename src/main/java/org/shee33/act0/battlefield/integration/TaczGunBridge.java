package org.shee33.act0.battlefield.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.List;

/**
 * TaCZ锛圱imeless and Classics Zero锛?b>寮硅嵂涓庢崲寮圭姸鎬?/b>鐨勫弽灏勮蒋渚濊禆妗ワ紝渚涗綔鎴?HUD 鐨勬鍣ㄦ爮浣跨敤銆?
 *
 * <p>鏈ā缁勪笉鍦ㄧ紪璇戞湡渚濊禆 TaCZ銆備笌 Arcade 鐨?{@code TaczBridge}锛堝彧绠￠厤浠讹級鍒绘剰鍒嗗紑锛氶偅涓ˉ鍦?
 * 鍙︿竴涓粨搴撱€佷笖鎴樺湴瀵?Arcade 涔熷彧鏄彲閫変緷璧栵紝璧?鎴樺湴鈫掑弽灏?Arcade鈫掑弽灏?TaCZ"涓よ烦浼氳寮硅嵂鏄剧ず
 * 骞崇櫧渚濊禆 Arcade 鏄惁鍦ㄥ満銆?
 *
 * <p><b>閫愭柟娉曢檷绾?/b>锛氭瘡涓弽灏勭洰鏍囧崟鐙В鏋愶紝浠讳綍涓€涓己澶卞彧璁╁搴斿姛鑳藉洖閫€锛屼笉浼氭嫋鍨暣涓ˉ銆?
 * 杩欐槸鍥犱负 TaCZ 鍦?1.20.1 鍒嗘敮涓?1.0.x 涓?1.1.x 鐨?API 骞朵笉涓€鑷达紙渚嬪 {@code useInventoryAmmo}
 * 鍙湁 1.1.x 鎵嶆湁锛夛紝鑰岀帺瀹舵湇涓婅窇鐨勫叿浣撶増鏈笉鍙楁垜浠帶鍒躲€?
 *
 * <p>API 渚濇嵁 TaCZ 浠撳簱 {@code MCModderAnchor/TACZ} 鍒嗘敮 {@code 1.20.1}锛坱ag 1.1.8-hotfix锛夋牳瀵广€?
 */
public final class TaczGunBridge {

    private static Class<?> iGunClass;
    private static Class<?> iAmmoClass;
    private static Class<?> iAmmoBoxClass;

    private static Method getIGunOrNull;
    private static Method gunGetCurrentAmmoCount;
    private static Method gunHasBulletInBarrel;
    private static Method gunUseDummyAmmo;
    private static Method gunGetDummyAmmoAmount;
    /** 浠?TaCZ 1.1.x 瀛樺湪锛?.0.x 涓嬩负 null锛岃涓?涓嶅惎鐢ㄨ儗鍖呯洿璇?銆?*/
    private static Method gunUseInventoryAmmo;

    private static Method ammoIsAmmoOfGun;
    private static Method boxIsAmmoBoxOfGun;
    private static Method boxGetAmmoCount;
    private static Method boxIsCreative;
    private static Method boxIsAllTypeCreative;

    private static Method gunGetGunId;
    private static Method gunSetDummyAmmoAmount;
    private static Method timelessGetClientGunIndex;
    private static Method timelessGetCommonGunIndex;
    private static Method clientIndexGetGunData;
    private static Method clientIndexGetName;
    private static Method gunDataGetBolt;
    private static Object openBoltConstant;

    private static Method builderCreate;
    private static Method builderSetId;
    private static Method builderSetFireMode;
    private static Method builderSetAmmoCount;
    private static Method builderSetAmmoInBarrel;
    private static Method builderSetHeatData;
    private static Method builderBuild;
    /**
     * {@code CommonGunIndex#getGunData}銆?b>鎯版€цВ鏋?/b>锛氬杩欎釜绫诲仛鏂规硶瑙ｆ瀽闇€瑕侀摼鎺?TaCZ 鑷甫鐨?
     * luaj锛岃€岄偅涓嶅湪鏈ā缁勭殑娴嬭瘯 classpath 涓娾€斺€旀斁杩涢潤鎬佸垵濮嬪寲浼氳鏁存潯閫犳灙閾捐矾鍦ㄦ祴璇曠幆澧冮噷琚垽瀹氫负
     * 涓嶅彲鐢ㄣ€傜湡姝ｈ皟鐢ㄥ畠鏃讹紙鏈嶅姟鍣ㄨ繍琛屾湡锛塴uaj 蹇呭畾鍦ㄥ満銆?
     */
    @Nullable
    private static Method commonIndexGetGunData;
    private static Method gunDataGetFireModeSet;
    private static Method gunDataGetAmmoAmount;
    private static Method gunDataHasHeatData;

    private static Method operatorFromLivingEntity;
    private static Method operatorGetSynReloadState;
    private static Method reloadStateGetStateType;
    private static Method reloadStateGetCountDown;
    private static Method stateTypeIsReloading;

    // ---- TaCZ API 鍚嶃€傞泦涓斁缃苟鐢?TaczGunBridgeTest 閽夋锛氳繖浜涘瓧绗︿覆鎷奸敊涓嶄細鎶ラ敊锛?
    // 鍙細璁╁搴斿姛鑳介潤榛樺け鐏碉紙寮€鍙戜腑灏卞樊鐐规妸 getSynReloadState 鍐欐垚 getSyncReloadState锛夈€?
    static final String CLASS_I_GUN = "com.tacz.guns.api.item.IGun";
    static final String CLASS_I_AMMO = "com.tacz.guns.api.item.IAmmo";
    static final String CLASS_I_AMMO_BOX = "com.tacz.guns.api.item.IAmmoBox";
    static final String CLASS_GUN_OPERATOR = "com.tacz.guns.api.entity.IGunOperator";
    static final String CLASS_RELOAD_STATE = "com.tacz.guns.api.entity.ReloadState";
    static final String CLASS_RELOAD_STATE_TYPE = "com.tacz.guns.api.entity.ReloadState$StateType";
    static final String M_GET_I_GUN_OR_NULL = "getIGunOrNull";
    static final String M_CURRENT_AMMO = "getCurrentAmmoCount";
    static final String M_HAS_BULLET_IN_BARREL = "hasBulletInBarrel";
    static final String M_USE_DUMMY_AMMO = "useDummyAmmo";
    static final String M_DUMMY_AMMO_AMOUNT = "getDummyAmmoAmount";
    static final String M_USE_INVENTORY_AMMO = "useInventoryAmmo";
    static final String M_IS_AMMO_OF_GUN = "isAmmoOfGun";
    static final String M_IS_AMMO_BOX_OF_GUN = "isAmmoBoxOfGun";
    static final String M_BOX_AMMO_COUNT = "getAmmoCount";
    static final String M_BOX_IS_CREATIVE = "isCreative";
    static final String M_BOX_IS_ALL_TYPE_CREATIVE = "isAllTypeCreative";
    static final String M_FROM_LIVING_ENTITY = "fromLivingEntity";
    static final String M_GET_SYN_RELOAD_STATE = "getSynReloadState";
    static final String M_GET_STATE_TYPE = "getStateType";
    static final String M_GET_COUNT_DOWN = "getCountDown";
    static final String M_IS_RELOADING = "isReloading";
    static final String CLASS_TIMELESS_API = "com.tacz.guns.api.TimelessAPI";
    static final String CLASS_CLIENT_GUN_INDEX = "com.tacz.guns.client.resource.index.ClientGunIndex";
    static final String CLASS_GUN_DATA = "com.tacz.guns.resource.pojo.data.gun.GunData";
    static final String CLASS_BOLT = "com.tacz.guns.resource.pojo.data.gun.Bolt";
    static final String M_GET_GUN_ID = "getGunId";
    static final String M_SET_DUMMY_AMMO_AMOUNT = "setDummyAmmoAmount";
    static final String M_GET_CLIENT_GUN_INDEX = "getClientGunIndex";
    static final String M_GET_COMMON_GUN_INDEX = "getCommonGunIndex";
    static final String M_GET_GUN_DATA = "getGunData";
    static final String M_GET_NAME = "getName";
    static final String M_GET_BOLT = "getBolt";
    static final String ENUM_OPEN_BOLT = "OPEN_BOLT";

    static final String CLASS_GUN_ITEM_BUILDER = "com.tacz.guns.api.item.builder.GunItemBuilder";
    static final String CLASS_COMMON_GUN_INDEX = "com.tacz.guns.resource.index.CommonGunIndex";
    static final String CLASS_FIRE_MODE = "com.tacz.guns.api.item.gun.FireMode";
    static final String M_BUILDER_CREATE = "create";
    static final String M_BUILDER_SET_ID = "setId";
    static final String M_BUILDER_SET_FIRE_MODE = "setFireMode";
    static final String M_BUILDER_SET_AMMO_COUNT = "setAmmoCount";
    static final String M_BUILDER_SET_AMMO_IN_BARREL = "setAmmoInBarrel";
    static final String M_BUILDER_SET_HEAT_DATA = "setHeatData";
    static final String M_BUILDER_BUILD = "build";
    static final String M_GET_FIRE_MODE_SET = "getFireModeSet";
    static final String M_GET_AMMO_AMOUNT = "getAmmoAmount";
    static final String M_HAS_HEAT_DATA = "hasHeatData";

    private static final boolean AVAILABLE;

    static {
        boolean gunOk = false;
        try {
            iGunClass = Class.forName(CLASS_I_GUN);
            getIGunOrNull = iGunClass.getMethod(M_GET_I_GUN_OR_NULL, ItemStack.class);
            gunGetCurrentAmmoCount = iGunClass.getMethod(M_CURRENT_AMMO, ItemStack.class);
            gunOk = true;
        } catch (Throwable ignored) {
            gunOk = false;
        }
        AVAILABLE = gunOk;

        gunHasBulletInBarrel = optional(iGunClass, M_HAS_BULLET_IN_BARREL, ItemStack.class);
        gunUseDummyAmmo = optional(iGunClass, M_USE_DUMMY_AMMO, ItemStack.class);
        gunGetDummyAmmoAmount = optional(iGunClass, M_DUMMY_AMMO_AMOUNT, ItemStack.class);
        gunUseInventoryAmmo = optional(iGunClass, M_USE_INVENTORY_AMMO, ItemStack.class);

        gunSetDummyAmmoAmount = optional(iGunClass, M_SET_DUMMY_AMMO_AMOUNT, ItemStack.class, int.class);

        // 閫愪釜瑙ｆ瀽鑰屼笉鏄杩涗竴涓?try锛氭妸瀹冧滑缁戝湪涓€璧锋椂锛屼换浣曚竴涓洰鏍囪В鏋愬け璐ラ兘浼氳繛鍧愭竻绌哄叾浣欑洰鏍囥€?
        // CommonGunIndex 鐨勬柟娉曡В鏋愭伆濂介渶瑕侀摼鎺?TaCZ 鑷甫鐨?luaj锛岀己瀹冨氨浼氭嫋鍨暣鏉￠€犳灙閾捐矾銆?
        Class<?> builderClass = classOrNull(CLASS_GUN_ITEM_BUILDER);
        Class<?> fireModeClass = classOrNull(CLASS_FIRE_MODE);
        Class<?> gunDataClass = classOrNull(CLASS_GUN_DATA);
        builderCreate = optional(builderClass, M_BUILDER_CREATE);
        builderSetId = optional(builderClass, M_BUILDER_SET_ID, ResourceLocation.class);
        builderSetFireMode = fireModeClass == null
                ? null : optional(builderClass, M_BUILDER_SET_FIRE_MODE, fireModeClass);
        builderSetAmmoCount = optional(builderClass, M_BUILDER_SET_AMMO_COUNT, int.class);
        builderSetAmmoInBarrel = optional(builderClass, M_BUILDER_SET_AMMO_IN_BARREL, boolean.class);
        builderSetHeatData = optional(builderClass, M_BUILDER_SET_HEAT_DATA, boolean.class);
        builderBuild = optional(builderClass, M_BUILDER_BUILD);
        gunDataGetFireModeSet = optional(gunDataClass, M_GET_FIRE_MODE_SET);
        gunDataGetAmmoAmount = optional(gunDataClass, M_GET_AMMO_AMOUNT);
        gunDataHasHeatData = optional(gunDataClass, M_HAS_HEAT_DATA);

        try {
            gunGetGunId = iGunClass == null ? null : iGunClass.getMethod(M_GET_GUN_ID, ItemStack.class);
            Class<?> timelessApi = Class.forName(CLASS_TIMELESS_API);
            timelessGetCommonGunIndex = optional(timelessApi, M_GET_COMMON_GUN_INDEX, ResourceLocation.class);
            timelessGetClientGunIndex = timelessApi.getMethod(M_GET_CLIENT_GUN_INDEX, ResourceLocation.class);
            clientIndexGetGunData = Class.forName(CLASS_CLIENT_GUN_INDEX).getMethod(M_GET_GUN_DATA);
            clientIndexGetName = Class.forName(CLASS_CLIENT_GUN_INDEX).getMethod(M_GET_NAME);
            gunDataGetBolt = Class.forName(CLASS_GUN_DATA).getMethod(M_GET_BOLT);
            for (Object constant : Class.forName(CLASS_BOLT).getEnumConstants()) {
                if (ENUM_OPEN_BOLT.equals(((Enum<?>) constant).name())) {
                    openBoltConstant = constant;
                }
            }
        } catch (Throwable ignored) {
            openBoltConstant = null;
        }

        try {
            iAmmoClass = Class.forName(CLASS_I_AMMO);
            ammoIsAmmoOfGun = iAmmoClass.getMethod(M_IS_AMMO_OF_GUN, ItemStack.class, ItemStack.class);
        } catch (Throwable ignored) {
            iAmmoClass = null;
        }
        try {
            iAmmoBoxClass = Class.forName(CLASS_I_AMMO_BOX);
            boxIsAmmoBoxOfGun = iAmmoBoxClass.getMethod(M_IS_AMMO_BOX_OF_GUN, ItemStack.class, ItemStack.class);
            boxGetAmmoCount = iAmmoBoxClass.getMethod(M_BOX_AMMO_COUNT, ItemStack.class);
            boxIsCreative = optional(iAmmoBoxClass, M_BOX_IS_CREATIVE, ItemStack.class);
            boxIsAllTypeCreative = optional(iAmmoBoxClass, M_BOX_IS_ALL_TYPE_CREATIVE, ItemStack.class);
        } catch (Throwable ignored) {
            iAmmoBoxClass = null;
        }
        try {
            Class<?> operatorClass = Class.forName(CLASS_GUN_OPERATOR);
            operatorFromLivingEntity = operatorClass.getMethod(M_FROM_LIVING_ENTITY, LivingEntity.class);
            operatorGetSynReloadState = operatorClass.getMethod(M_GET_SYN_RELOAD_STATE);
            Class<?> reloadStateClass = Class.forName(CLASS_RELOAD_STATE);
            reloadStateGetStateType = reloadStateClass.getMethod(M_GET_STATE_TYPE);
            reloadStateGetCountDown = reloadStateClass.getMethod(M_GET_COUNT_DOWN);
            Class<?> stateTypeClass = Class.forName(CLASS_RELOAD_STATE_TYPE);
            stateTypeIsReloading = stateTypeClass.getMethod(M_IS_RELOADING);
        } catch (Throwable ignored) {
            operatorFromLivingEntity = null;
        }
    }

    @Nullable
    private static Class<?> classOrNull(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method optional(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, params);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private TaczGunBridge() {
    }

    /** 杩愯鏃舵槸鍚﹀瓨鍦?TaCZ 鏋 API銆?*/
    public static boolean isAvailable() {
        return AVAILABLE;
    }

    public static boolean isGun(ItemStack stack) {
        return iGun(stack) != null;
    }

    private static Object iGun(ItemStack stack) {
        if (!AVAILABLE || stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            return getIGunOrNull.invoke(null, stack);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 璇诲彇鏋 ID锛堝舰濡?{@code tacz:ak47}锛夛紱闈炴灙鎴栦笉鍙敤杩斿洖 {@code null}銆?
     *
     * <p>{@code /aew1 arena ... weapon add} 闈犲畠鎶婄鐞嗗憳涓绘墜鐨勬灙鐧昏杩涘湴鍥炬鍣ㄦ睜鈥斺€斿綍鍏ョ殑鏄?ID
     * 鑰屼笉鏄暣涓?ItemStack锛屾墍浠ョ帺瀹跺嚭鐢熸椂鎷垮埌鐨勬槸涓€鎶婂叏鏂扮殑骞插噣鏋紝涓嶄細缁ф壙绠＄悊鍛橀偅鎶婃灙韬笂鐨?
     * 閰嶄欢銆佺（鎹熶笌寮硅嵂鐘舵€併€?
     */
    @Nullable
    public static String gunId(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null || gunGetGunId == null) {
            return null;
        }
        try {
            Object id = gunGetGunId.invoke(gun, stack);
            return id == null ? null : id.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 鎸夋灙姊?ID 閫犱竴鎶?b>鍙互鐩存帴浣滄垬</b>鐨勬柊鏋紱閫犱笉鍑烘潵杩斿洖 {@link ItemStack#EMPTY}銆?
     *
     * <p><b>蹇呴』璧?TaCZ 鑷繁鐨?{@code GunItemBuilder}</b>锛屼笉鑳藉彧鏄?鏂板缓鐗╁搧 + 鍐?GunId"銆?
     * 鍙啓 GunId 寰楀埌鐨勬灙锛屽叾浣?NBT 鍏ㄩ儴缂虹渷锛岃€?TaCZ 鐨勭己鐪佸€兼剰鍛崇潃锛氬脊鍖?0 鍙戙€?
     * 灏勫嚮妯″紡 {@code UNKNOWN}锛堝鎴风鍙 BURST/AUTO 鍒嗘敮锛孶NKNOWN 浼氳惤鍒板崟鍙戣矾寰勶紝
     * 浜庢槸鑷姩姝ユ灙琛ㄧ幇寰楀儚鍗婅嚜鍔級銆佽啗鍐呮棤寮广€佽繃鐑暟鎹湭鍒濆鍖栥€傜帺瀹朵細鎷垮埌涓€鎶婃墦涓嶅搷鐨勬灙锛?
     * 鑰岃繖涓€鍒囨病鏈変换浣曟姤閿欌€斺€旀湰鏂规硶杩斿洖鐨勬槸闈炵┖鐗╁搧锛岃皟鐢ㄦ柟鐨勫け璐ュ憡璀︿篃涓嶄細瑙﹀彂銆?
     *
     * <p>鐢?Builder 杩橀『甯︽嬁鍒颁袱浠朵簨锛歿@code build()} 鍦ㄦ灙姊?ID 鏈 TaCZ 鍔犺浇鏃惰繑鍥?EMPTY
     * 锛堟浛浠ｄ竴娆″崟鐙殑瀛樺湪鎬ф牎楠岋級锛屼互鍙婃寜鏋 type 鏄犲皠鍒版纭殑鐗╁搧鈥斺€擳aCZ 骞堕潪鎵€鏈夋灙閮藉叡鐢?
     * 鍚屼竴涓墿鍝侊紝闄勫姞鍖呭彲浠ユ敞鍐屾柊鐨勬灙姊扮被鍨嬨€?
     */
    public static ItemStack createGun(@Nullable String gunId) {
        if (!AVAILABLE || builderCreate == null || gunId == null || gunId.isBlank()) {
            return ItemStack.EMPTY;
        }
        ResourceLocation id = ResourceLocation.tryParse(gunId);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        try {
            Object builder = builderCreate.invoke(null);
            builderSetId.invoke(builder, id);
            applyGunDefaults(builder, id);
            Object built = builderBuild.invoke(builder);
            return built instanceof ItemStack stack ? stack : ItemStack.EMPTY;
        } catch (Throwable e) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 鎸夎鏋殑瀹氫箟琛ラ綈鍑哄巶鐘舵€侊細棣栦釜灏勫嚮妯″紡銆佹弧寮瑰專銆佽啗鍐呬竴鍙戙€佽繃鐑暟鎹€?
     *
     * <p>涓?TaCZ 鑷繁缁欏垱閫犳ā寮忕墿鍝佹爮鍙戞灙鏃剁殑鍋氭硶淇濇寔涓€鑷淬€傚彇涓嶅埌鏋瀹氫箟鏃堕潤榛樿烦杩団€斺€?
     * 姝ゆ椂 {@code build()} 涔熶細杩斿洖 EMPTY锛岃皟鐢ㄦ柟浼氳蛋鍛婅鍒嗘敮锛屼笉闇€瑕佸湪杩欓噷閲嶅鎶ラ敊銆?
     */
    private static void applyGunDefaults(Object builder, ResourceLocation id) throws ReflectiveOperationException {
        Object gunData = gunData(id);
        if (gunData == null) {
            return;
        }
        if (builderSetFireMode != null && gunDataGetFireModeSet != null
                && gunDataGetFireModeSet.invoke(gunData) instanceof List<?> modes && !modes.isEmpty()) {
            builderSetFireMode.invoke(builder, modes.get(0));
        }
        if (builderSetAmmoCount != null && gunDataGetAmmoAmount != null
                && gunDataGetAmmoAmount.invoke(gunData) instanceof Integer amount && amount > 0) {
            builderSetAmmoCount.invoke(builder, amount);
        }
        if (builderSetAmmoInBarrel != null) {
            builderSetAmmoInBarrel.invoke(builder, true);
        }
        if (builderSetHeatData != null && gunDataHasHeatData != null) {
            builderSetHeatData.invoke(builder, Boolean.TRUE.equals(gunDataHasHeatData.invoke(gunData)));
        }
    }

    /**
     * 蹇収鏋鐨?b>闈欐€侀厤缃?/b>锛堥厤浠?灏勫嚮妯″紡/婵€鍏夐鑹茬瓑锛変负 SNBT 瀛楃涓诧紱闈炴灙鎴栦笉鍙敤杩斿洖 {@code null}銆?
    /**
     * 蹇収鏋鐨?b>闈欐€侀厤缃?/b>锛堥厤浠?灏勫嚮妯″紡/婵€鍏夐鑹茬瓑锛変负 SNBT 瀛楃涓诧紱闈炴灙鎴栦笉鍙敤杩斿洖 {@code null}銆?
     *
     * <p>鍙繚瀛橀潤鎬佹暟鎹細寮瑰專浣欏脊銆佽啗鍐呬竴鍙戙€佺儹閲忋€佽櫄鎷熷寮广€佺粡楠岃繖浜?b>鍔ㄦ€佺姸鎬?/b>琚墺鎺夆€斺€?
     * 鐜╁鍑虹敓搴旀嬁鍒颁竴鎶婃弧寮瑰專銆侀浂鐑噺鐨勬柊鏋紝鑰屼笉鏄户鎵跨鐞嗗憳涓婃灦鏃剁殑鏃х姸鎬併€?
     *
     * <p>鐢?SNBT 瀛楃涓茶€屼笉鏄師濮?NBT锛氶厤瑁呮ā鍨?{@code LoadoutPresetDef} 鏄?MC-free 鐨勬牳蹇冮€昏緫锛?
     * 涓嶈兘寮曠敤 {@link CompoundTag}锛涘瓧绗︿覆缁?{@link TagParser} 鍙棤鎹熻繕鍘燂紝涓斾笌 Arcade 閰嶈
     * 鐩綍锛坽@code LoadoutApplier.fromSnbt}锛夌殑鏃㈡湁鏍煎紡淇濇寔涓€鑷淬€?
     */
    @Nullable
    public static String snapshotGunNbt(ItemStack stack) {
        if (!isGun(stack) || stack.getTag() == null) {
            return null;
        }
        try {
            CompoundTag copy = stack.getTag().copy();
            for (String key : GUN_DYNAMIC_NBT_KEYS) {
                copy.remove(key);
            }
            return copy.isEmpty() ? null : copy.toString();
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * 鎶?{@link #snapshotGunNbt} 鐨勫揩鐓у悎骞跺洖涓€鎶婃柊鏋紱绌哄揩鐓?瑙ｆ瀽澶辫触闈欓粯璺宠繃锛堜繚鎸佸共鍑€鏋級銆?
     */
    public static void applyGunSnapshot(ItemStack gun, @Nullable String snbt) {
        if (gun == null || gun.isEmpty() || snbt == null || snbt.isBlank()) {
            return;
        }
        try {
            CompoundTag parsed = TagParser.parseTag(snbt);
            if (!parsed.isEmpty()) {
                gun.getOrCreateTag().merge(parsed);
            }
        } catch (Throwable ignored) {
            // 蹇収鎹熷潖鏃朵繚鐣欏共鍑€鏋紝涓嶉樆濉炲彂瑁呫€?
        }
    }

    /**
     * 蹇収鏃堕渶瑕佸墧闄ょ殑 TaCZ 鍔ㄦ€佺姸鎬侀敭锛堟灙姊?NBT 椤跺眰閿級銆?
     * 渚濇嵁 TaCZ 1.1.8 鐨?GunItemDataAccessor 甯搁噺锛欸unCurrentAmmoCount/HasBulletInBarrel/
     * HeatAmount/OverHeated/DummyAmmo/MaxDummyAmmo/GunLevelExp銆?
     */
    static final String[] GUN_DYNAMIC_NBT_KEYS = {
            "GunCurrentAmmoCount", "HasBulletInBarrel", "HeatAmount", "OverHeated",
            "DummyAmmo", "MaxDummyAmmo", "GunLevelExp"};

    /**
     * 璇ユ灙涓€涓脊鍖ｇ殑瀹归噺锛涙煡涓嶅埌杩斿洖 {@code -1}銆?
     *
     * <p>鐧昏姝﹀櫒鏃剁敤瀹冩帹瀵奸粯璁ゅ寮光€斺€斿寮圭粰澶氬皯鍙湁瀵圭潃寮瑰專瀹归噺鎵嶆湁鎰忎箟锛?
     * 涓€涓浐瀹氬父鏁板鐙欏嚮鏋拰鏈烘灙蹇呯劧鏈変竴杈规槸閿欑殑銆?
     */
    /**
     * 璇ユ灙涓€涓脊鍖ｇ殑瀹归噺锛涙煡涓嶅埌杩斿洖 {@code -1}銆?
     *
     * <p>鐧昏姝﹀櫒鏃剁敤瀹冩帹瀵奸粯璁ゅ寮光€斺€斿寮圭粰澶氬皯鍙湁瀵圭潃寮瑰專瀹归噺鎵嶆湁鎰忎箟锛?
     * 涓€涓浐瀹氬父鏁板鐙欏嚮鏋拰鏈烘灙蹇呯劧鏈変竴杈规槸閿欑殑銆?
     */
    public static int magazineSize(@Nullable String gunId) {
        if (!AVAILABLE || gunDataGetAmmoAmount == null || gunId == null) {
            return -1;
        }
        ResourceLocation id = ResourceLocation.tryParse(gunId);
        if (id == null) {
            return -1;
        }
        try {
            Object gunData = gunData(id);
            return gunData != null && gunDataGetAmmoAmount.invoke(gunData) instanceof Integer n ? n : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** TaCZ 鏈嶅姟绔晶鐨勬灙姊板畾涔夛紱鏈姞杞借鏋垨瑙ｆ瀽涓嶅埌杩斿洖 {@code null}銆?*/
    @Nullable
    private static Object gunData(ResourceLocation id) throws ReflectiveOperationException {
        if (timelessGetCommonGunIndex == null) {
            return null;
        }
        Object optional = timelessGetCommonGunIndex.invoke(null, id);
        if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
            return null;
        }
        Object index = opt.get();
        if (commonIndexGetGunData == null) {
            commonIndexGetGunData = optional(index.getClass(), M_GET_GUN_DATA);
            if (commonIndexGetGunData == null) {
                return null;
            }
        }
        return commonIndexGetGunData.invoke(index);
    }

    /**
     * 鍐欏叆铏氭嫙澶囧脊鏁帮紝鍚屾椂涔熷氨鎶婅繖鎶婃灙鍒囧埌浜嗚櫄鎷熷寮规ā寮忥紙TaCZ 鐢?{@code DummyAmmo} 鏍囩鏄惁瀛樺湪
     * 鍒ゆ柇妯″紡锛岃 {@code GunItemDataAccessor#useDummyAmmo}锛夈€?
     *
     * <p><b>鍒绘剰涓嶈 MaxDummyAmmo</b>锛氳浜嗕笂闄愪箣鍚庡脊鑽灏辫ˉ涓嶈繃鍒濆鍊硷紝鑰屽ぇ鎴樺満鐨勮ˉ缁欓亾鍏?
     * 姝ｆ槸闈?琛ュ埌瓒呰繃鍑虹敓鎼哄甫閲?鏉ユ彁渚涙垬鏈环鍊笺€備笉璁句笂闄愬垯琛ョ粰涓嶅彈闄愩€?
     *
     * @return 鏄惁鍐欏叆鎴愬姛
     */
    public static boolean setDummyAmmo(ItemStack stack, int amount) {
        Object gun = iGun(stack);
        if (gun == null || gunSetDummyAmmoAmount == null || amount < 0) {
            return false;
        }
        try {
            gunSetDummyAmmoAmount.invoke(gun, stack, amount);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 寮瑰專鍐呬綑寮癸紱闈炴灙鎴栦笉鍙敤杩斿洖 -1銆?*/
    public static int currentAmmo(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null) {
            return -1;
        }
        try {
            Object v = gunGetCurrentAmmoCount.invoke(gun, stack);
            return v instanceof Integer i ? i : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * 璇ユ灙鏄惁涓哄紑鑶涘緟鍑伙紙OPEN_BOLT锛夈€傚紑鑶涙灙姊版病鏈?鐙珛鐨勮啗鍐呬竴鍙?锛孴aCZ 瀹樻柟 HUD 鍥犳
     * 涓嶆妸 {@link #hasBulletInBarrel} 璁″叆寮瑰專鏄剧ず鏁般€?
     *
     * <p>鍙栧€奸摼鏄?{@code IGun.getGunId 鈫?TimelessAPI.getClientGunIndex 鈫?ClientGunIndex
     * .getGunData 鈫?GunData.getBolt}锛岀函瀹㈡埛绔祫婧愮储寮曘€備换浣曚竴鐜В鏋愪笉鍒伴兘杩斿洖 false锛?
     * 閫€鍥?鎸夐棴鑶涘鐞?鈥斺€旂粷澶у鏁版灙姊版槸闂啗锛岃繖鏄洿鎺ヨ繎姝ｇ‘鐨勯粯璁ゃ€?
     */
    public static boolean isOpenBolt(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null || gunGetGunId == null || timelessGetClientGunIndex == null
                || clientIndexGetGunData == null || gunDataGetBolt == null || openBoltConstant == null) {
            return false;
        }
        try {
            Object gunId = gunGetGunId.invoke(gun, stack);
            if (gunId == null) {
                return false;
            }
            Object optional = timelessGetClientGunIndex.invoke(null, gunId);
            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return false;
            }
            Object gunData = clientIndexGetGunData.invoke(opt.get());
            return gunData != null && openBoltConstant.equals(gunDataGetBolt.invoke(gunData));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 鎸夋灙姊?ID 瑙ｆ瀽<b>瀹㈡埛绔湰鍦板寲鏄剧ず鍚?/b>锛堝 {@code tacz:m24} 鈫?"M24"锛夛紱
     * 鏋鍖呮湭鍔犺浇 / 涓撶敤鏈嶅姟绔?/ 浠讳綍涓€鐜В鏋愪笉鍒拌繑鍥?{@code null}锛堣皟鐢ㄦ柟鍥為€€鍒扮墿鍝佸悕锛夈€?
     *
     * <p>涓轰粈涔堜笉鑳介潬鐗╁搧 hover 鍚嶏細TaCZ 鎵€鏈夋灙鍏辩敤涓€涓墿鍝侊紙{@code tacz:modern_kinetic_gun}锛夛紝
     * 瀹冪殑 hover 鍚嶆槸鍚屼竴涓?translatable key锛坽@code item.tacz.modern_kinetic_gun}锛夛紝鎹㈡垚鍝妸鏋?
     * 閮戒竴鏍凤紝鎷夸笉鍒版灙鐨勭湡瀹炲瀷鍙枫€傜湡瀹炲悕瀛楀湪<b>瀹㈡埛绔祫婧愮储寮?/b>閲岋細
     * {@code TimelessAPI.getClientGunIndex(gunId) 鈫?ClientGunIndex.getName}銆?
     */
    @Nullable
    public static String clientGunDisplayName(@Nullable String gunId) {
        if (gunId == null || gunId.isBlank() || timelessGetClientGunIndex == null || clientIndexGetName == null) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(gunId);
        if (id == null) {
            return null;
        }
        try {
            Object optional = timelessGetClientGunIndex.invoke(null, id);
            if (!(optional instanceof java.util.Optional<?> opt) || opt.isEmpty()) {
                return null;
            }
            Object name = clientIndexGetName.invoke(opt.get());
            if (name == null) {
                return null;
            }
            // getName() 鍙兘鏄函鍚嶅瓧涔熷彲鑳芥槸缈昏瘧 key锛宼ranslatable 涓ゆ潯璺兘涓嶄細鍑洪敊
            return net.minecraft.network.chat.Component.translatable(name.toString()).getString();
        } catch (Throwable e) {
            return null;
        }
    }

    /** 鏄惁鏈夊凡涓婅啗鐨勪竴鍙戯紙闂啗鏋 TaCZ 鑷繁鐨?HUD 浼氭妸瀹冭鍏ュ脊鍖ｆ暟锛夈€?*/
    public static boolean hasBulletInBarrel(ItemStack stack) {
        Object gun = iGun(stack);
        if (gun == null || gunHasBulletInBarrel == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(gunHasBulletInBarrel.invoke(gun, stack));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 澶囧脊鏁般€俆aCZ <b>娌℃湁</b>鐜版垚鐨勫寮规帴鍙ｏ紝鍏跺畼鏂?HUD 涔熸槸閬嶅巻鑳屽寘缁熻 {@code IAmmo} 涓?
     * {@code IAmmoBox}锛岃繖閲岀収鎼悓涓€濂楀垽瀹氾細
     * <ol>
     *   <li>铏氭嫙澶囧脊妯″紡锛坽@code useDummyAmmo}锛夆啋 鐩存帴鍙?{@code getDummyAmmoAmount}锛?/li>
     *   <li>鑳屽寘鐩磋妯″紡锛?.1.x 鐨?{@code useInventoryAmmo}锛夆啋 TaCZ 瀹樻柟 HUD 姝ゆ椂涓嶆樉绀哄寮癸紝
     *       杩斿洖 -1 璁╄皟鐢ㄦ柟鐪佺暐锛?/li>
     *   <li>鍚﹀垯閬嶅巻鑳屽寘锛氭暎瑁呭脊鎸夊爢鍙犳暟绱姞锛屽脊鑽鎸夊叾鍐呭惈寮归噺绱姞锛屽垱閫犲脊鑽鐩存帴 9999銆?/li>
     * </ol>
     *
     * @return 澶囧脊鏁帮紱-1 琛ㄧず"涓嶉€傜敤/涓嶆樉绀?
     */
    public static int reserveAmmo(Player player, ItemStack gunStack) {
        Object gun = iGun(gunStack);
        if (gun == null || player == null) {
            return -1;
        }
        try {
            if (gunUseDummyAmmo != null && Boolean.TRUE.equals(gunUseDummyAmmo.invoke(gun, gunStack))) {
                if (gunGetDummyAmmoAmount == null) {
                    return -1;
                }
                Object v = gunGetDummyAmmoAmount.invoke(gun, gunStack);
                return v instanceof Integer i ? i : -1;
            }
            if (gunUseInventoryAmmo != null
                    && Boolean.TRUE.equals(gunUseInventoryAmmo.invoke(gun, gunStack))) {
                return -1;
            }
        } catch (Throwable e) {
            return -1;
        }
        return countInventoryAmmo(player.getInventory(), gunStack);
    }

    private static int countInventoryAmmo(Inventory inventory, ItemStack gunStack) {
        int total = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack slot = inventory.getItem(i);
            if (slot.isEmpty()) {
                continue;
            }
            Object item = slot.getItem();
            try {
                if (iAmmoClass != null && iAmmoClass.isInstance(item)
                        && Boolean.TRUE.equals(ammoIsAmmoOfGun.invoke(item, gunStack, slot))) {
                    total += slot.getCount();
                    continue;
                }
                if (iAmmoBoxClass != null && iAmmoBoxClass.isInstance(item)
                        && Boolean.TRUE.equals(boxIsAmmoBoxOfGun.invoke(item, gunStack, slot))) {
                    if (isCreativeBox(item, slot)) {
                        return 9999;
                    }
                    Object v = boxGetAmmoCount.invoke(item, slot);
                    if (v instanceof Integer c) {
                        total += c;
                    }
                }
            } catch (Throwable ignored) {
                // 鍗曚釜妲戒綅鍒ゅ畾澶辫触涓嶅奖鍝嶅叾浣欑粺璁?
            }
        }
        return total;
    }

    private static boolean isCreativeBox(Object item, ItemStack slot) throws Exception {
        if (boxIsAllTypeCreative != null && Boolean.TRUE.equals(boxIsAllTypeCreative.invoke(item, slot))) {
            return true;
        }
        return boxIsCreative != null && Boolean.TRUE.equals(boxIsCreative.invoke(item, slot));
    }

    /** 鐜╁褰撳墠鏄惁姝ｅ湪鎹㈠脊銆?*/
    public static boolean isReloading(LivingEntity entity) {
        Object stateType = reloadStateType(entity);
        if (stateType == null || stateTypeIsReloading == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(stateTypeIsReloading.invoke(stateType));
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * 鎹㈠脊鍓╀綑姣鏁帮紱鏈湪鎹㈠脊鎴栦笉鍙敤杩斿洖 -1銆?
     *
     * <p>TaCZ 鍙悓姝?鍓╀綑鏃堕棿"锛屼笉缁欐€绘椂闀库€斺€旀€绘椂闀胯棌鍦?{@code GunData.getReloadData()} 閲岋紝鍙栧畠
     * 闇€瑕佸啀绌?{@code TimelessAPI 鈫?ClientGunIndex 鈫?GunData} 涓夊眰鍙嶅皠銆侶UD 鍙渶瑕佷竴鏉¤繘搴︽潯锛?
     * 鐢辫皟鐢ㄦ柟璁板綍鏈鎹㈠脊瑙傛祴鍒扮殑鏈€澶у墿浣欏€煎綋鍒嗘瘝鍗冲彲锛屼笉鍊煎緱涓烘澶氭帴涓変釜鍙嶅皠鐩爣銆?
     */
    public static long reloadCountDownMs(LivingEntity entity) {
        Object state = reloadState(entity);
        if (state == null || reloadStateGetCountDown == null) {
            return -1L;
        }
        try {
            Object v = reloadStateGetCountDown.invoke(state);
            return v instanceof Long l ? l : -1L;
        } catch (Throwable e) {
            return -1L;
        }
    }

    private static Object reloadState(LivingEntity entity) {
        if (entity == null || operatorFromLivingEntity == null || operatorGetSynReloadState == null) {
            return null;
        }
        try {
            Object operator = operatorFromLivingEntity.invoke(null, entity);
            return operator == null ? null : operatorGetSynReloadState.invoke(operator);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object reloadStateType(LivingEntity entity) {
        Object state = reloadState(entity);
        if (state == null || reloadStateGetStateType == null) {
            return null;
        }
        try {
            return reloadStateGetStateType.invoke(state);
        } catch (Throwable e) {
            return null;
        }
    }
}
