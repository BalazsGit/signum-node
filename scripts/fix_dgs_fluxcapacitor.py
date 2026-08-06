#!/usr/bin/env python3
"""Add missing FluxCapacitor parameter to DGS test constructors."""
import os, re

HANDLER_DIR = "test/java/application/module/node/web/api/http/handler"

files_to_fix = {
    "DGSDeliveryTest.java": r"new DGSDelivery\(([^)]+)\)",
    "DGSFeedbackTest.java": r"new DGSFeedback\(([^)]+)\)",
    "DGSListingTest.java": r"new DGSListing\(([^)]+)\)",
    "DGSPriceChangeTest.java": r"new DGSPriceChange\(([^)]+)\)",
    "DGSPurchaseTest.java": r"new DGSPurchase\(([^)]+)\)",
    "DGSQuantityChangeTest.java": r"new DGSQuantityChange\(([^)]+)\)",
}

# Also need to check for QuickMocker.latestValueFluxCapacitor in setUp and add fluxcapacitor field
for test_file, pattern in files_to_fix.items():
    filepath = os.path.join(HANDLER_DIR, test_file)
    if not os.path.exists(filepath):
        print(f"NOT FOUND: {test_file}")
        continue

    with open(filepath, 'r', encoding='utf-8') as f:
        content = f.read()

    # 1. Add QuickMocker import for fluxcapacitor if missing
    if "QuickMocker.latestValueFluxCapacitor" not in content and "fluxCapacitor" not in content.lower():
        content = content.replace(
            "import application.module.node.fluxcapacitor.FluxCapacitor;",
            "import application.module.node.fluxcapacitor.FluxCapacitor;\nimport application.module.node.common.QuickMocker;"
        )

    # 2. Find the new XXX(...) call and add fluxcapacitor as last param
    def add_fluxcap(params):
        params = params.strip()
        # Check if already has QuickMocker reference
        if "fluxCapacitor" not in params and "QuickMocker" not in params:
            return params + ", QuickMocker.latestValueFluxCapacitor()"
        return params

    content = re.sub(pattern, lambda m: f"new {test_file.replace('Test.java','')}({add_fluxcap(m.group(1))})", content)

    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(content)
    print(f"FIXED: {test_file}")

print("Done!")