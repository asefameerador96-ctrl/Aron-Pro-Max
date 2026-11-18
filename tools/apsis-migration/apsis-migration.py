#!/usr/bin/env python3
"""Entry point: python3 -I tools/apsis-migration/apsis-migration.py profile|run|reconcile|finalize ..."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from apsis_migration.runner import main
sys.exit(main())
