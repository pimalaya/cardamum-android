# Convenience for `nix-shell`; `nix develop` (the flake) is the source
# of truth. Evaluates the flake's default devShell via flake-compat.
(import (fetchTarball {
  url = "https://github.com/edolstra/flake-compat/archive/refs/heads/master.tar.gz";
}) { src = ./.; }).shellNix
