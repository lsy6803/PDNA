@echo off
title 线刷脚本 [请勿选中窗口，否则卡住活该，卡住按右键或回车或放大缩小窗口恢复]
if not exist bin\windows (
   echo.
   echo.未解压！
   echo.
   pause
   exit
)
cls

if exist "images\super.img.zst" (
   echo.正在转换 super.img.zst
   bin\windows\zstd.exe --rm -d "images\super.img.zst" -o "images\super.img"
   if %ERRORLEVEL% neq 0 (
      echo.转换失败！
      pause
      exit
   )
)
cls
echo.
set /p root_init_boot="是否需要刷入root权限 (y/n) "
echo.

echo.
set /p WIPE="是否需要清除数据？(y/n) "
echo.
echo.刷完super可能会卡一会，请耐心等待！！！
echo.若卡在 ^<waiting for any device^> 请自行寻找驱动
echo.

rem
echo.正在刷入系统底层
bin\windows\fastboot flash boot images\boot.img --slot all
bin\windows\fastboot flash modem images\modem.img --slot all
bin\windows\fastboot flash recovery images\recovery.img

echo.重启进入fastbootd继续刷入剩余系统底层
echo.请勿切断与电脑连接
bin\windows\fastboot reboot fastboot

if /i "%root_init_boot%" equ "y" (
echo.刷入root权限
   bin\windows\fastboot flash init_boot images\root_init_boot.img --slot all
)

bin\windows\fastboot flash abl images\abl.img --slot all
bin\windows\fastboot flash aop images\aop.img --slot all
bin\windows\fastboot flash aop_config images\aop_config.img --slot all
bin\windows\fastboot flash bluetooth images\bluetooth.img --slot all
bin\windows\fastboot flash cpucp images\cpucp.img --slot all
bin\windows\fastboot flash cpucp_dtb images\cpucp_dtb.img --slot all
bin\windows\fastboot flash dtbo images\dtbo.img --slot all
bin\windows\fastboot flash devcfg images\devcfg.img --slot all
bin\windows\fastboot flash engineering_cdt images\engineering_cdt.img --slot all
bin\windows\fastboot flash dsp images\dsp.img --slot all
bin\windows\fastboot flash featenabler images\featenabler.img --slot all
bin\windows\fastboot flash imagefv images\imagefv.img --slot all
bin\windows\fastboot flash keymaster images\keymaster.img --slot all
bin\windows\fastboot flash oplus_sec images\oplus_sec.img --slot all
bin\windows\fastboot flash oplusstanvbk images\oplusstanvbk.img --slot all
bin\windows\fastboot flash pdp images\pdp.img --slot all
bin\windows\fastboot flash pdp_cdb images\pdp_cdb.img --slot all
bin\windows\fastboot flash pvmfw images\pvmfw.img --slot all
bin\windows\fastboot flash qupfw images\qupfw.img --slot all
bin\windows\fastboot flash shrm images\shrm.img --slot all
bin\windows\fastboot flash hyp images\hyp.img --slot all
bin\windows\fastboot flash recovery images\recovery.img --slot all
bin\windows\fastboot flash soccp_dcd images\soccp_dcd.img --slot all
bin\windows\fastboot flash soccp_debug images\soccp_debug.img --slot all
bin\windows\fastboot flash splash images\splash.img --slot all
bin\windows\fastboot flash spuservice images\spuservice.img --slot all
bin\windows\fastboot flash tz images\tz.img --slot all
bin\windows\fastboot flash uefi images\uefi.img --slot all
bin\windows\fastboot flash uefisecapp images\uefisecapp.img --slot all
bin\windows\fastboot flash vbmeta images\vbmeta.img --slot all
bin\windows\fastboot flash vbmeta_system images\vbmeta_system.img --slot all
bin\windows\fastboot flash vbmeta_vendor images\vbmeta_vendor.img --slot all
bin\windows\fastboot flash vendor_boot images\vendor_boot.img --slot all
bin\windows\fastboot flash xbl images\xbl.img --slot all
bin\windows\fastboot flash xbl_config images\xbl_config.img --slot all
bin\windows\fastboot flash xbl_ramdump images\xbl_ramdump.img --slot all

echo.正在刷入super，请耐心等待！！！
if exist  images\super.img bin\windows\fastboot flash super images\super.img
echo.刷完super可能会卡一会，请耐心等待！！！

if /i "%WIPE%" equ "y" (
   bin\windows\fastboot erase metadata
   bin\windows\fastboot erase userdata
)

bin\windows\fastboot set_active a
bin\windows\fastboot reboot
pause