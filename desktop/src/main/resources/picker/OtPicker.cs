// Windows' modern file and folder picker (IFileOpenDialog), for OuterTune desktop.
//
// Java's own FileDialog shows the old common dialog, and JFileChooser is drawn by Swing; neither
// looks like the picker every other Windows app opens. This is that picker, reached through COM.
// Compiled once by PowerShell into the app's data folder and reused - see WindowsPicker.kt.
using System;
using System.Runtime.InteropServices;
using System.Text;

public static class OtPicker
{
    [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
    private class FileOpenDialogCoClass { }

    [ComImport, Guid("d57c7288-d4ad-4768-be02-9d969532d960"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IFileOpenDialog
    {
        [PreserveSig] int Show(IntPtr hwndOwner);
        void SetFileTypes(uint cFileTypes, [MarshalAs(UnmanagedType.LPArray)] FilterSpec[] rgFilterSpec);
        void SetFileTypeIndex(uint iFileType);
        void GetFileTypeIndex(out uint piFileType);
        void Advise(IntPtr pfde, out uint pdwCookie);
        void Unadvise(uint dwCookie);
        void SetOptions(uint fos);
        void GetOptions(out uint pfos);
        void SetDefaultFolder(IShellItem psi);
        void SetFolder(IShellItem psi);
        void GetFolder(out IShellItem ppsi);
        void GetCurrentSelection(out IShellItem ppsi);
        void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string pszName);
        void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string pszName);
        void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string pszTitle);
        void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string pszText);
        void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string pszLabel);
        void GetResult(out IShellItem ppsi);
        void AddPlace(IShellItem psi, int fdap);
        void SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string pszDefaultExtension);
        void Close(int hr);
        void SetClientGuid(ref Guid guid);
        void ClearClientData();
        void SetFilter(IntPtr pFilter);
        void GetResults(out IShellItemArray ppenum);
        void GetSelectedItems(out IShellItemArray ppsai);
    }

    [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItem
    {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppv);
        void GetParent(out IShellItem ppsi);
        void GetDisplayName(uint sigdnName, [MarshalAs(UnmanagedType.LPWStr)] out string ppszName);
        void GetAttributes(uint sfgaoMask, out uint psfgaoAttribs);
        void Compare(IShellItem psi, uint hint, out int piOrder);
    }

    [ComImport, Guid("b63ea76d-1f85-456f-a19c-48159efa858b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    private interface IShellItemArray
    {
        void BindToHandler(IntPtr pbc, ref Guid bhid, ref Guid riid, out IntPtr ppvOut);
        void GetPropertyStore(int flags, ref Guid riid, out IntPtr ppv);
        void GetPropertyDescriptionList(IntPtr keyType, ref Guid riid, out IntPtr ppv);
        void GetAttributes(int attribFlags, uint sfgaoMask, out uint psfgaoAttribs);
        void GetCount(out uint pdwNumItems);
        void GetItemAt(uint dwIndex, out IShellItem ppsi);
        void EnumItems(out IntPtr ppenumShellItems);
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct FilterSpec
    {
        [MarshalAs(UnmanagedType.LPWStr)] public string Name;
        [MarshalAs(UnmanagedType.LPWStr)] public string Spec;
    }

    private const uint FOS_PICKFOLDERS = 0x20;
    private const uint FOS_FORCEFILESYSTEM = 0x40;
    private const uint FOS_ALLOWMULTISELECT = 0x200;
    private const uint FOS_PATHMUSTEXIST = 0x800;
    private const uint FOS_FILEMUSTEXIST = 0x1000;
    private const uint SIGDN_FILESYSPATH = 0x80058000;

    /// Shows the picker; returns the chosen paths, one per line, or an empty string if cancelled.
    public static string Pick(bool folders, bool multi, string title, string filterName, string filterSpec)
    {
        var dialog = (IFileOpenDialog)new FileOpenDialogCoClass();
        uint options;
        dialog.GetOptions(out options);
        options |= FOS_FORCEFILESYSTEM | FOS_PATHMUSTEXIST;
        options |= folders ? FOS_PICKFOLDERS : FOS_FILEMUSTEXIST;
        if (multi) options |= FOS_ALLOWMULTISELECT;
        dialog.SetOptions(options);
        if (!string.IsNullOrEmpty(title)) dialog.SetTitle(title);
        if (!folders && !string.IsNullOrEmpty(filterSpec))
        {
            dialog.SetFileTypes(1, new[] { new FilterSpec { Name = filterName, Spec = filterSpec } });
        }
        if (dialog.Show(IntPtr.Zero) != 0) return "";

        IShellItemArray items;
        dialog.GetResults(out items);
        uint count;
        items.GetCount(out count);
        var paths = new StringBuilder();
        for (uint i = 0; i < count; i++)
        {
            IShellItem item;
            items.GetItemAt(i, out item);
            string path;
            item.GetDisplayName(SIGDN_FILESYSPATH, out path);
            paths.AppendLine(path);
        }
        return paths.ToString();
    }
}
