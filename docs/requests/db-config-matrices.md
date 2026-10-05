# Request (db → lead): default matrices for cfg.app.home_tiles and cfg.web.menu_by_role

docs/24 s9.5 (contract v1.1) gives these defaults only in words: `cfg.app.home_tiles` "per role; Loyalty Point and
Photo Capture only for accounts with programme outlets" and `cfg.web.menu_by_role` "the seed matrix of docs/19 s5.3
mapped to the roles of s8.5". V0009 registers both keys with the default `{}` and the bound text in `bounds_rule`.

**Asked:** the concrete JSON for both (or confirm that the db lane should derive `menu_by_role` from docs/19 s5.3);
a follow-up migration then updates the defaults. Until then the web and app lanes must not read an empty object as
"no menus": treat `{}` as "built-in default".
