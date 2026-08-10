vim.filetype.add({
    extension = {pam = "pam"},
    pattern = {[".*%.pam%.php"] = "pam"},
})

vim.lsp.config.pam_native = {
    cmd = {vim.fn.getcwd() .. "/vendor/bin/pam-native-language-server"},
    filetypes = {"pam"},
    root_markers = {"pam.json", "pam-native.json", "composer.json", ".git"},
}

vim.lsp.enable("pam_native")
