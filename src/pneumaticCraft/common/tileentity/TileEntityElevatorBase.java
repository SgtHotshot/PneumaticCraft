package pneumaticCraft.common.tileentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.ChunkPosition;
import net.minecraftforge.common.util.ForgeDirection;

import org.apache.commons.lang3.tuple.Pair;

import pneumaticCraft.api.tileentity.IPneumaticMachine;
import pneumaticCraft.common.block.BlockElevatorBase;
import pneumaticCraft.common.block.Blockss;
import pneumaticCraft.common.item.Itemss;
import pneumaticCraft.common.thirdparty.computercraft.LuaConstant;
import pneumaticCraft.common.thirdparty.computercraft.LuaMethod;
import pneumaticCraft.common.util.PneumaticCraftUtils;
import pneumaticCraft.lib.Log;
import pneumaticCraft.lib.ModIds;
import pneumaticCraft.lib.Names;
import pneumaticCraft.lib.PneumaticValues;
import pneumaticCraft.lib.Sounds;
import pneumaticCraft.lib.TileEntityConstants;
import cpw.mods.fml.common.Optional;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;

public class TileEntityElevatorBase extends TileEntityPneumaticBase implements IInventory, IGUITextFieldSensitive{
    public boolean[] sidesConnected = new boolean[6];
    public float oldExtension;
    public float extension;
    public float targetExtension;
    public int speedStartup;
    private boolean firstRun = true;
    private int soundCounter;
    private boolean isStopped; //used for sounds
    private TileEntityElevatorBase coreElevator;
    public int redstoneMode;
    public int[] floorHeights = new int[0];//list of every floor of Elevator Callers.
    private final HashMap<Integer, String> floorNames = new HashMap<Integer, String>();
    private int maxFloorHeight;
    private int redstoneInputLevel;//current redstone input level
    private List<Pair<ForgeDirection, IPneumaticMachine>> connectedMachines;

    private ItemStack[] inventory = new ItemStack[4];
    public static final int UPGRADE_SLOT_1 = 0;
    public static final int UPGRADE_SLOT_4 = 3;

    public TileEntityElevatorBase(){
        super(PneumaticValues.DANGER_PRESSURE_ELEVATOR, PneumaticValues.MAX_PRESSURE_ELEVATOR, PneumaticValues.VOLUME_ELEVATOR);
        setUpgradeSlots(new int[]{UPGRADE_SLOT_1, 1, 2, UPGRADE_SLOT_4});
    }

    @Override
    public void updateEntity(){
        if(firstRun) {
            firstRun = false;
            updateConnections();
        }
        oldExtension = extension;
        if(isCoreElevator()) {
            super.updateEntity();
            if(!worldObj.isRemote && isControlledByRedstone()) {
                float oldTargetExtension = targetExtension;
                float maxExtension = getMaxElevatorHeight();
                targetExtension = redstoneInputLevel * maxExtension / 15;
                if(targetExtension > oldExtension && getPressure(ForgeDirection.UNKNOWN) < PneumaticValues.MIN_PRESSURE_ELEVATOR) targetExtension = oldExtension; // only ascent when there's enough pressure
                if(oldTargetExtension != targetExtension) sendNBTPacket(256D);
            }
            float speedMultiplier = getSpeedMultiplierFromUpgrades(getUpgradeSlots());

            String soundName = null;
            if(extension < targetExtension) {
                if(!worldObj.isRemote && getPressure(ForgeDirection.UNKNOWN) < PneumaticValues.MIN_PRESSURE_ELEVATOR) {
                    targetExtension = extension;
                    sendNBTPacket(256D);
                }
                soundName = Sounds.ELEVATOR_MOVING;
                if(extension < targetExtension - TileEntityConstants.ELEVATOR_SLOW_EXTENSION) {
                    extension += TileEntityConstants.ELEVATOR_SPEED_FAST * speedMultiplier;
                } else {
                    extension += TileEntityConstants.ELEVATOR_SPEED_SLOW * speedMultiplier;
                }
                if(extension > targetExtension) {
                    extension = targetExtension;
                    updateFloors();
                }
                if(isStopped) {
                    soundName = Sounds.ELEVATOR_START;
                    isStopped = false;
                }
                moveEntities();
                addAir((int)((oldExtension - extension) * PneumaticValues.USAGE_ELEVATOR * (getSpeedUsageMultiplierFromUpgrades(getUpgradeSlots()) / speedMultiplier)), ForgeDirection.UNKNOWN);// substract the ascended distance from the air reservoir.
            }
            if(extension > targetExtension) {
                soundName = Sounds.ELEVATOR_MOVING;
                if(extension > targetExtension + TileEntityConstants.ELEVATOR_SLOW_EXTENSION) {
                    extension -= TileEntityConstants.ELEVATOR_SPEED_FAST * speedMultiplier;
                } else {
                    extension -= TileEntityConstants.ELEVATOR_SPEED_SLOW * speedMultiplier;
                }
                if(extension < targetExtension) {
                    extension = targetExtension;
                    updateFloors();
                }
                if(isStopped) {
                    soundName = Sounds.ELEVATOR_START;
                    isStopped = false;
                }
            }
            if(oldExtension == extension && !isStopped) {
                soundName = Sounds.ELEVATOR_STOP;
                isStopped = true;
                soundCounter = 0;
            }

            if(soundCounter > 0) soundCounter--;
            if(soundName != null && worldObj.isRemote && soundCounter == 0) {
                worldObj.playSound(xCoord + 0.5, yCoord + 0.5, zCoord + 0.5, soundName, 0.1F, 1.0F, true);
                soundCounter = 10;
            }
        }

    }

    private void moveEntities(){
        AxisAlignedBB aabb = AxisAlignedBB.getBoundingBox(xCoord, yCoord + 1, zCoord, xCoord + 1, yCoord + extension + 1, zCoord + 1);
        List<Entity> entityList = worldObj.getEntitiesWithinAABBExcludingEntity(null, aabb);
        for(Entity entity : entityList) {
            entity.moveEntity(0, extension - oldExtension + 0.05F, 0);
        }
    }

    @Override
    public void handleGUIButtonPress(int buttonID, EntityPlayer player){
        redstoneMode++;
        if(redstoneMode > 1) redstoneMode = 0;

        int i = -1;
        TileEntity te = worldObj.getTileEntity(xCoord, yCoord - 1, zCoord);
        while(te instanceof TileEntityElevatorBase) {
            ((TileEntityElevatorBase)te).redstoneMode = redstoneMode;
            i--;
            te = worldObj.getTileEntity(xCoord, yCoord + i, zCoord);
        }

        sendDescriptionPacket();
    }

    private boolean isControlledByRedstone(){
        return redstoneMode == 0;
    }

    public void updateRedstoneInputLevel(){
        int i = 0;
        int maxRedstone = 0;
        boolean isIndirectlyPowered = false;
        while(worldObj.getBlock(xCoord, yCoord + i, zCoord) == Blockss.elevatorBase) {
            maxRedstone = Math.max(maxRedstone, worldObj.getBlockPowerInput(xCoord, yCoord + i, zCoord));
            if(maxRedstone == 0 && !isIndirectlyPowered) isIndirectlyPowered = worldObj.isBlockIndirectlyGettingPowered(xCoord, yCoord + i, zCoord);
            i--;
        }
        redstoneInputLevel = maxRedstone > 0 ? maxRedstone : isIndirectlyPowered ? 15 : 0;
    }

    @Override
    public void onNeighborTileUpdate(){
        super.onNeighborTileUpdate();
        connectedMachines = null;
        if(!isCoreElevator()) {
            getCoreElevator().onNeighborTileUpdate();
        }
    }

    public float getMaxElevatorHeight(){
        return maxFloorHeight;
    }

    public void updateMaxElevatorHeight(){
        int i = -1;
        do {
            i++;
        } while(worldObj.getBlock(xCoord, yCoord + i + 1, zCoord) == Blockss.elevatorFrame);
        int elevatorBases = 0;
        do {
            elevatorBases++;
        } while(worldObj.getBlock(xCoord, yCoord - elevatorBases, zCoord) == Blockss.elevatorBase);

        maxFloorHeight = Math.min(i, elevatorBases * 4);
    }

    // NBT methods-----------------------------------------------
    @Override
    public void readFromNBT(NBTTagCompound tag){
        super.readFromNBT(tag);
        extension = tag.getFloat("extension");
        targetExtension = tag.getFloat("targetExtension");
        redstoneMode = tag.getInteger("redstoneMode");
        if(!tag.hasKey("maxFloorHeight")) {//backwards compatibility implementation.
            updateMaxElevatorHeight();
        } else {
            maxFloorHeight = tag.getInteger("maxFloorHeight");
        }
        for(int i = 0; i < 6; i++) {
            sidesConnected[i] = tag.getBoolean("sideConnected" + i);
        }
        floorHeights = tag.getIntArray("floorHeights");

        floorNames.clear();
        NBTTagList floorNameList = tag.getTagList("floorNames", 10);
        for(int i = 0; i < floorNameList.tagCount(); i++) {
            NBTTagCompound floorName = floorNameList.getCompoundTagAt(i);
            floorNames.put(floorName.getInteger("floorHeight"), floorName.getString("floorName"));
        }

        // Read in the ItemStacks in the inventory from NBT
        NBTTagList tagList = tag.getTagList("Items", 10);
        inventory = new ItemStack[inventory.length];
        for(int i = 0; i < tagList.tagCount(); ++i) {
            NBTTagCompound tagCompound = tagList.getCompoundTagAt(i);
            byte slot = tagCompound.getByte("Slot");
            if(slot >= 0 && slot < inventory.length) {
                inventory[slot] = ItemStack.loadItemStackFromNBT(tagCompound);
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag){
        super.writeToNBT(tag);
        tag.setFloat("extension", extension);
        tag.setFloat("targetExtension", targetExtension);
        tag.setInteger("redstoneMode", redstoneMode);
        tag.setInteger("maxFloorHeight", maxFloorHeight);
        for(int i = 0; i < 6; i++) {
            tag.setBoolean("sideConnected" + i, sidesConnected[i]);
        }

        tag.setIntArray("floorHeights", floorHeights);
        NBTTagList floorNameList = new NBTTagList();
        for(int key : floorNames.keySet()) {
            NBTTagCompound floorNameTag = new NBTTagCompound();
            floorNameTag.setInteger("floorHeight", key);
            floorNameTag.setString("floorName", floorNames.get(key));
            floorNameList.appendTag(floorNameTag);
        }
        tag.setTag("floorNames", floorNameList);

        // Write the ItemStacks in the inventory to NBT
        NBTTagList tagList = new NBTTagList();
        for(int currentIndex = 0; currentIndex < inventory.length; ++currentIndex) {
            if(inventory[currentIndex] != null) {
                NBTTagCompound tagCompound = new NBTTagCompound();
                tagCompound.setByte("Slot", (byte)currentIndex);
                inventory[currentIndex].writeToNBT(tagCompound);
                tagList.appendTag(tagCompound);
            }
        }
        tag.setTag("Items", tagList);

    }

    public void updateConnections(){
        for(ForgeDirection direction : ForgeDirection.VALID_DIRECTIONS) {
            TileEntity te = worldObj.getTileEntity(xCoord + direction.offsetX, yCoord + direction.offsetY, zCoord + direction.offsetZ);
            if(te instanceof IPneumaticMachine) {
                sidesConnected[direction.ordinal()] = ((IPneumaticMachine)te).isConnectedTo(direction.getOpposite());
            } else {
                sidesConnected[direction.ordinal()] = false;
            }
        }
        if(worldObj.getBlock(xCoord, yCoord + 1, zCoord) != Blockss.elevatorBase) {
            coreElevator = this;
            int i = -1;
            TileEntity te = worldObj.getTileEntity(xCoord, yCoord - 1, zCoord);
            while(te instanceof TileEntityElevatorBase) {
                ((TileEntityElevatorBase)te).coreElevator = this;
                i--;
                te = worldObj.getTileEntity(xCoord, yCoord + i, zCoord);
            }
        }
        sendDescriptionPacket();
    }

    public void moveInventoryToThis(){
        TileEntity te = worldObj.getTileEntity(xCoord, yCoord + 1, zCoord);
        if(te instanceof TileEntityElevatorBase) {
            for(int i = 0; i < getSizeInventory(); i++) {
                inventory[i] = ((TileEntityElevatorBase)te).inventory[i];
                ((TileEntityElevatorBase)te).inventory[i] = null;
            }
        }
    }

    public void updateFloors(){
        List<Integer> floorList = new ArrayList<Integer>();
        List<ChunkPosition> callerList = new ArrayList<ChunkPosition>();
        for(int i = 0; i == 0 || worldObj.getBlock(xCoord, yCoord + i, zCoord) == Blockss.elevatorFrame; i++) {
            boolean registeredThisFloor = false;
            for(ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
                if(dir != ForgeDirection.UP && dir != ForgeDirection.DOWN) {
                    if(worldObj.getBlock(xCoord + dir.offsetX, yCoord + i + 2, zCoord + dir.offsetZ) == Blockss.elevatorCaller) {
                        callerList.add(new ChunkPosition(xCoord + dir.offsetX, yCoord + i + 2, zCoord + dir.offsetZ));
                        if(!registeredThisFloor) floorList.add(i);
                        registeredThisFloor = true;
                    }
                }
            }
        }
        floorHeights = new int[floorList.size()];
        for(int i = 0; i < floorHeights.length; i++) {
            floorHeights[i] = floorList.get(i);
        }

        double buttonHeight = 0.06D;
        double buttonSpacing = 0.02D;
        TileEntityElevatorCaller.ElevatorButton[] elevatorButtons = new TileEntityElevatorCaller.ElevatorButton[floorHeights.length];
        int columns = (elevatorButtons.length - 1) / 12 + 1;
        for(int j = 0; j < columns; j++) {
            for(int i = j * 12; i < floorHeights.length && i < j * 12 + 12; i++) {
                elevatorButtons[i] = new TileEntityElevatorCaller.ElevatorButton(0.2D + 0.6D / columns * j, 0.5D + (Math.min(floorHeights.length, 12) - 2) * (buttonSpacing + buttonHeight) / 2 - i % 12 * (buttonHeight + buttonSpacing), 0.58D / columns, buttonHeight, i, floorHeights[i]);
                elevatorButtons[i].setColor(floorHeights[i] == targetExtension ? 0 : 1, 1, floorHeights[i] == targetExtension ? 0 : 1);
                String floorName = floorNames.get(floorHeights[i]);
                if(floorName != null) {
                    elevatorButtons[i].buttonText = floorName;
                } else {
                    floorNames.put(floorHeights[i], elevatorButtons[i].buttonText);
                }
            }
        }

        for(ChunkPosition p : callerList) {
            TileEntity te = worldObj.getTileEntity(p.chunkPosX, p.chunkPosY, p.chunkPosZ);
            if(te instanceof TileEntityElevatorCaller) {
                int callerFloorHeight = p.chunkPosY - yCoord - 2;
                int callerFloor = -1;
                for(TileEntityElevatorCaller.ElevatorButton floor : elevatorButtons) {
                    if(floor.floorHeight == callerFloorHeight) {
                        callerFloor = floor.floorNumber;
                        break;
                    }
                }
                if(callerFloor == -1) {
                    Log.error("Error while updating elevator floors! This will cause a indexOutOfBoundsException, index = -1");
                }
                ((TileEntityElevatorCaller)te).setEmittingRedstone(PneumaticCraftUtils.areFloatsEqual(targetExtension, extension, 0.1F) && PneumaticCraftUtils.areFloatsEqual(extension, callerFloorHeight, 0.1F));
                ((TileEntityElevatorCaller)te).setFloors(elevatorButtons, callerFloor);
            }
        }
    }

    public void goToFloor(int floor){
        if(getCoreElevator().isControlledByRedstone()) getCoreElevator().handleGUIButtonPress(0, null);
        if(floor >= 0 && floor < floorHeights.length) targetExtension = Math.min(floorHeights[floor], getMaxElevatorHeight());
        updateFloors();
        sendNBTPacket(256D);
    }

    // INVENTORY METHODS-
    // ------------------------------------------------------------

    /**
     * Returns the number of slots in the inventory.
     */
    @Override
    public int getSizeInventory(){
        return getCoreElevator().inventory.length;
    }

    /**
     * Returns the stack in slot i
     */
    @Override
    public ItemStack getStackInSlot(int slot){
        return getCoreElevator().inventory[slot];
    }

    public ItemStack getRealStackInSlot(int slot){
        return inventory[slot];
    }

    @Override
    public ItemStack decrStackSize(int slot, int amount){

        ItemStack itemStack = getStackInSlot(slot);
        if(itemStack != null) {
            if(itemStack.stackSize <= amount) {
                setInventorySlotContents(slot, null);
            } else {
                itemStack = itemStack.splitStack(amount);
                if(itemStack.stackSize == 0) {
                    setInventorySlotContents(slot, null);
                }
            }
        }

        return itemStack;
    }

    @Override
    public ItemStack getStackInSlotOnClosing(int slot){

        ItemStack itemStack = getStackInSlot(slot);
        if(itemStack != null) {
            setInventorySlotContents(slot, null);
        }
        return itemStack;
    }

    @Override
    public void setInventorySlotContents(int slot, ItemStack itemStack){
        // super.setInventorySlotContents(slot, itemStack);
        getCoreElevator().inventory[slot] = itemStack;
        if(itemStack != null && itemStack.stackSize > getInventoryStackLimit()) {
            itemStack.stackSize = getInventoryStackLimit();
        }
    }

    @Override
    public int getInventoryStackLimit(){

        return 64;
    }

    @Override
    public String getInventoryName(){
        return Names.ELEVATOR;
    }

    @Override
    public void openInventory(){}

    @Override
    public void closeInventory(){}

    @Override
    public boolean isItemValidForSlot(int i, ItemStack itemstack){
        return itemstack.getItem() == Itemss.machineUpgrade;
    }

    @Override
    public boolean isUseableByPlayer(EntityPlayer var1){
        return isGuiUseableByPlayer(var1);
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox(){
        return AxisAlignedBB.getBoundingBox(xCoord, yCoord, zCoord, xCoord + 1, yCoord + 1 + extension, zCoord + 1);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared(){
        return 65536D;
    }

    private TileEntityElevatorBase getCoreElevator(){
        if(coreElevator == null) {
            coreElevator = BlockElevatorBase.getCoreTileEntity(worldObj, xCoord, yCoord, zCoord);
        }
        return coreElevator;
    }

    public boolean isCoreElevator(){
        return getCoreElevator() == this;
    }

    @Override
    public boolean isConnectedTo(ForgeDirection side){
        return side != ForgeDirection.UP && side != ForgeDirection.DOWN || worldObj.getBlock(xCoord, yCoord + side.offsetY, zCoord) != Blockss.elevatorBase;
    }

    @Override
    public void addAir(int amount, ForgeDirection side){
        if(isCoreElevator()) {
            super.addAir(amount, side);
        } else {
            getCoreElevator().addAir(amount, side);
        }
    }

    @Override
    public float getPressure(ForgeDirection sideRequested){
        if(isCoreElevator()) {
            return super.getPressure(sideRequested);
        } else {
            return getCoreElevator().getPressure(sideRequested);
        }
    }

    @Override
    public int getCurrentAir(ForgeDirection sideRequested){
        if(isCoreElevator()) {
            return super.getCurrentAir(sideRequested);
        } else {
            return getCoreElevator().getCurrentAir(sideRequested);
        }
    }

    @Override
    public List<Pair<ForgeDirection, IPneumaticMachine>> getConnectedPneumatics(){
        if(connectedMachines == null) {
            connectedMachines = super.getConnectedPneumatics();
            TileEntity te = getTileCache()[ForgeDirection.DOWN.ordinal()].getTileEntity();
            while(te instanceof TileEntityElevatorBase) {
                connectedMachines.addAll(((TileEntityElevatorBase)te).getConnectedPneumatics());
                te = ((TileEntityElevatorBase)te).getTileCache()[ForgeDirection.DOWN.ordinal()].getTileEntity();
            }
        }
        return connectedMachines;
    }

    @Override
    public void setText(int textFieldID, String text){
        setFloorName(textFieldID, text);
    }

    @Override
    public String getText(int textFieldID){
        return getFloorName(textFieldID);
    }

    public String getFloorName(int floor){
        return floor < floorHeights.length ? floorNames.get(floorHeights[floor]) : "";
    }

    public void setFloorName(int floor, String name){
        if(floor < floorHeights.length) {
            floorNames.put(floorHeights[floor], name);
            updateFloors();
        }
    }

    @Override
    public boolean isGuiUseableByPlayer(EntityPlayer par1EntityPlayer){
        return worldObj.getTileEntity(xCoord, yCoord, zCoord) == this;
    }

    @Override
    public boolean hasCustomInventoryName(){
        return true;
    }

    /*
     * COMPUTERCRAFT API
     */

    @Override
    public String getType(){
        return "elevator";
    }

    @Override
    @Optional.Method(modid = ModIds.COMPUTERCRAFT)
    protected void addLuaMethods(){
        super.addLuaMethods();
        luaMethods.add(new LuaConstant("getMinWorkingPressure", PneumaticValues.MIN_PRESSURE_ELEVATOR));
        luaMethods.add(new LuaMethod("setHeight"){
            @Override
            public Object[] call(IComputerAccess computer, ILuaContext context, Object[] args) throws LuaException, InterruptedException{
                if(args.length == 1) {
                    getCoreElevator().targetExtension = Math.min(((Double)args[0]).floatValue(), getCoreElevator().getMaxElevatorHeight());
                    if(getCoreElevator().isControlledByRedstone()) getCoreElevator().handleGUIButtonPress(0, null);
                    getCoreElevator().sendNBTPacket(256D);
                    return null;
                } else {
                    throw new IllegalArgumentException("setHeight does take one argument (height)");
                }
            }
        });

        luaMethods.add(new LuaMethod("setExternalControl"){
            @Override
            public Object[] call(IComputerAccess computer, ILuaContext context, Object[] args) throws LuaException, InterruptedException{
                if(args.length == 1) {
                    if((Boolean)args[0] && getCoreElevator().isControlledByRedstone() || !(Boolean)args[0] && !getCoreElevator().isControlledByRedstone()) {
                        getCoreElevator().handleGUIButtonPress(0, null);
                    }
                    return null;
                } else {
                    throw new IllegalArgumentException("setExternalControl does take one argument! (bool)");
                }
            }
        });
    }
}
