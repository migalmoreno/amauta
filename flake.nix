{
  inputs = {
    nixpkgs.url = "github:nixos/nixpkgs/nixos-unstable";
    systems.url = "github:nix-systems/default";
    tubo.url = "git+ssh://forgejo@auriga/migalmoreno/tubo";
    nx-router.url = "git+ssh://forgejo@auriga/migalmoreno/nx-router";
    nx-tailor.url = "git+ssh://forgejo@auriga/migalmoreno/nx-tailor";
    nx-mosaic.url = "git+ssh://forgejo@auriga/migalmoreno/nx-mosaic";
    fdroid-el.url = "git+ssh://forgejo@auriga/migalmoreno/fdroid.el";
    nyxt-el.url = "git+ssh://forgejo@auriga/migalmoreno/nyxt.el";
  };
  outputs =
    inputs@{ nixpkgs, systems, ... }:
    let
      eachSystem =
        f: nixpkgs.lib.genAttrs (import systems) (system: f (import nixpkgs { inherit system; }));
    in
    {
      devShells = eachSystem (pkgs: {
        default = pkgs.mkShell {
          buildInputs = with pkgs; [
            clojure
            nodejs
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
            ${toString (
              map
                (name: ''
                  (echo "#+SOURCE-DIR: ${inputs.${name}}" && cat ${./projects/${name}.org} ${
                    if
                      pkgs.lib.hasAttrByPath [
                        "packages"
                        pkgs.system
                        "docs"
                      ] inputs.${name}
                    then
                      "${inputs.${name}.packages.${pkgs.system}.docs}/index.org"
                    else if builtins.pathExists "${inputs.${name}}/README.org" then
                      "${inputs.${name}}/README.org"
                    else
                      "${inputs.${name}}/README"
                  }) > $tmpdir/${name}.org
                  ln -sf $tmpdir/${name}.org ./posts/projects/${name}.org
                '')
                (map (path: pkgs.lib.removeSuffix ".org" path) (builtins.attrNames (builtins.readDir ./projects)))
            )}
            cat > $tmpdir/preamble.el<< EOF
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
