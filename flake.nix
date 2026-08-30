{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";
  };
  outputs =
    { nixpkgs, systems, ... }:
    let
      eachSystem =
        f: nixpkgs.lib.genAttrs (import systems) (system: f (import nixpkgs { inherit system; }));
    in
    {
      devShells = eachSystem (pkgs: {
        default = pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
            gh
            git
            nodejs
            openssh
            wrangler
            (emacs.pkgs.withPackages (
              epkgs: with epkgs; [
                htmlize
                clojure-mode
                nix-mode
                nginx-mode
                yaml-mode
                rainbow-delimiters
                (trivialBuild {
                  pname = "ox-html-stable-ids";
                  version = "0.1.1";
                  src = pkgs.fetchFromGitHub {
                    owner = "jeffkreeftmeijer";
                    repo = "ox-html-stable-ids.el";
                    rev = "0.1.1";
                    hash = "sha256-58GQlri6Hs9MTgCgrwnI+NYGgDgfAghWNv1V02Fgjuo=";
                  };
                })
              ]
            ))
          ];
          shellHook = ''
            tmpdir=$(mktemp -d)
            cat > $tmpdir/preamble.el << EOF
            (require 'ox-html-stable-ids)
            (org-html-stable-ids-add)
            (setq org-html-stable-ids t)
            (require 'nix-mode)
            (require 'clojure-mode)
            (require 'nginx-mode)
            (require 'yaml-mode)
            (add-to-list 'auto-mode-alist '("\\.y[a]?ml\\'" . yaml-mode))
            (require 'rainbow-delimiters)
            (add-hook 'prog-mode-hook #'rainbow-delimiters-mode)
            EOF
            export HAUNT_ORG_READER_EMACS_PREAMBLE=$tmpdir/preamble.el
            export HAUNT_ORG_READER_USE_EMACSCLIENT=1
          '';
        };
      });
    };
}
