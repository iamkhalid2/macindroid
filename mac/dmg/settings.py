# dmgbuild settings for Macindroid's disk image (release.sh passes app= and background=).
# The window shows the app, an arrow to Applications, and how to get past the first-open warning.
app = defines["app"]  # noqa: F821 (dmgbuild provides `defines`)
files = [app]
symlinks = {"Applications": "/Applications"}
background = defines["background"]  # noqa: F821
window_rect = ((200, 120), (660, 500))
default_view = "icon-view"
icon_size = 112
text_size = 13
icon_locations = {"Macindroid.app": (180, 150), "Applications": (480, 150),
                  ".background.tiff": (330, 900)}  # out of sight, for Finders that show hidden files
show_status_bar = False
show_tab_view = False
show_toolbar = False
show_pathbar = False
show_sidebar = False
format = "UDZO"
