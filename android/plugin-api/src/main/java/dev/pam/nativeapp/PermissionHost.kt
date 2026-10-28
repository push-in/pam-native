package dev.pam.nativeapp

/** Permission requests owned by the current UI surface. */
interface PermissionHost {
    fun requestPamPermission(permission: String, callback: (Boolean) -> Unit)
    fun requestPamPermissions(permissions: Array<String>, callback: (Boolean) -> Unit)
    fun requestPamPermissionsAgain(permissions: Array<String>, callback: (Boolean) -> Unit)
}
