# intention: "TfUnknownProperty"
# fix: "Remove unknown property"
# position: 5: "non_property = """
#
resource "azurerm_orchestrated_virtual_machine_scale_set" "aws1" {
  location = ""
  name = ""
  platform_fault_domain_count = 0
  resource_group_name = ""

  data_disk {
    disk_size_gb         = 0
    lun                  = 0
    storage_account_type = ""
    caching              = ""
  }

  ami_mot = ""
}
