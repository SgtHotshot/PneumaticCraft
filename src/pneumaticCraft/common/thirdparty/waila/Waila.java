package pneumaticCraft.common.thirdparty.waila;

import mcp.mobius.waila.api.IWailaRegistrar;
import net.minecraft.creativetab.CreativeTabs;
import pneumaticCraft.api.tileentity.IPneumaticMachine;
import pneumaticCraft.common.thirdparty.IThirdParty;
import pneumaticCraft.common.tileentity.TileEntityPressureTube;
import cpw.mods.fml.common.event.FMLInterModComms;

public class Waila implements IThirdParty{

    @Override
    public void preInit(CreativeTabs pneumaticCraftTab){}

    @Override
    public void init(){
        FMLInterModComms.sendMessage("Waila", "register", "pneumaticCraft.common.thirdparty.waila.Waila.callbackRegister");
    }

    @Override
    public void postInit(){}

    @Override
    public void clientSide(){

    }

    public static void callbackRegister(IWailaRegistrar registrar){
        registrar.registerBodyProvider(new WailaHandler(), IPneumaticMachine.class);
        registrar.registerSyncedNBTKey("pneumatic", IPneumaticMachine.class);

        registrar.registerSyncedNBTKey("*", TileEntityPressureTube.class);
        registrar.registerBodyProvider(new WailaTubeModuleHandler(), TileEntityPressureTube.class);
        //TODO registrar.registerBodyProvider(new WailaHandler(), TileMultipart.class);
    }
}
