# Feature test checklist

Agent-owned. Mirrors the ForgeDroid checklist. Built from every menu item, click handler and preference in `app/src/main`. Mark each line `[x]` with the date and build commit when it passes on the emulator, or `[!]` with a note when it fails. Mark `[-]` with a reason when the feature does not exist on GitLab and the app hides it or shows a clear message.

Rules for every line:
- Do the action, then check the result on screen and on the server where it writes data. Opening a screen is not enough.
- For every list, open the first item and one more item.
- Check logcat after each action for 4xx/5xx responses, `Loading data failed` and `FATAL`. A 404 for `user/starred/...` when the repo is not starred is expected.
- Look for "null", "0" placeholders and raw `number|title` text in rows.
- Use a scratch repo on the test account for anything that writes data, and clean up afterwards.

Test against the local GitLab CE container (`http://10.0.2.2:8929`, accounts `alice`, `bob`, `root`, group `tanuki-org` with subgroup `sub`, seeded by `/tmp/tk/seed.py`). Run the News Feed and profile sections as a user and as an org from the account selector. Also open public projects on gitlab.com for the preset instances where no login is needed.

## 1. Accounts and login
- [ ] Add account dialog opens from the drawer account menu
- [ ] Dropdown lists gitlab.com, gitlab.gnome.org, invent.kde.org, salsa.debian.org, gitlab.freedesktop.org, gitgud.io, framagit.org, jihulab.com, gitlab.alpinelinux.org, Custom
- [ ] Custom shows the base URL input, other hosts hide it
- [ ] Token name input is editable and its value is used for the token
- [ ] Webview loads the sign-in page of the selected host
- [ ] Webview register link works
- [ ] Logging in creates a token with the chosen name (check the host's applications page) and saves the account
- [ ] Bad base URL shows a readable error
- [ ] Cancel closes the dialog without saving
- [ ] Login mode chooser fragment (if reachable) behaves
- [ ] Account selector in the News Feed lists the user and every org
- [ ] Switching accounts reloads the current screen
- [ ] Drawer header expands to My profile, Logout, Add account
- [ ] My profile opens the user profile
- [ ] Logout removes the account and returns to the login flow
- [ ] Cold start after logout and after login restores the right account
- [ ] Revoked token shows a readable error, not a crash

## 2. Drawer (`home_nav_drawer`)
- [ ] News Feed
- [ ] Notifications
- [ ] My repositories
- [ ] My issues
- [ ] My pull requests
- [ ] My gists
- [ ] Search
- [ ] Bookmarks and Stars
- [ ] Public Timeline (hidden or clear message on GitLab)
- [ ] Trending Repos (hidden or clear message on GitLab)
- [ ] GitHub Blog (hidden, since it is GitHub-only)
- [ ] Settings
- [ ] Add account, My profile, Logout from the header menu
- [ ] Back from each screen returns to the previous one without leaving the app unexpectedly
- [ ] Drawer swipe open and close, selected item is highlighted

## 3. News Feed
- [ ] Pull to refresh
- [ ] Scroll to load the next page
- [ ] Tap an avatar opens that user
- [ ] Tap a repo name opens the repo
- [ ] Push event row, tap row, tap each listed commit
- [ ] Create repository, branch and tag rows
- [ ] Delete branch and tag rows
- [ ] Issue opened, closed and reopened rows, tap through
- [ ] Issue comment row, tap through
- [ ] Pull request opened, closed, reopened and merged rows, tap through (opens the PR screen)
- [ ] Pull request comment row, tap through (opens the PR screen, not the issue screen)
- [ ] Fork, star and release rows, tap through
- [ ] Unsupported event types are skipped without a blank row
- [ ] No "null", "pull request 0" or raw `number|title` text
- [ ] Same checks for an org feed

## 4. Notifications (`notification_list_menu`, `notification_menu`)
- [ ] List loads, empty state shows when there are none
- [ ] Filter menu: Unread, All, Participating
- [ ] Mark all as read
- [ ] Row tap opens the issue, PR or commit
- [ ] Row avatar tap opens the user
- [ ] Row action button: Mark as read
- [ ] Row action button: Unsubscribe
- [ ] Notification polling and the system notification (Settings > Notifications on)

## 5. My repositories and repository lists (`repo_list_menu`, `repo_filter_*`, `repo_sort`)
- [ ] My repositories list loads and paginates
- [ ] Tap a repo
- [ ] Search icon filters the list
- [ ] Filter and Sort menu opens
- [ ] Type filters for the logged-in user: All, Owner, Member, Public, Private, Sources, Forks, Watched
- [ ] Type filters for another user: All, Owner, Member, Sources, Forks, Watched, Starred
- [ ] Type filters for an org: All, Sources, Forks
- [ ] Sort: name A-Z, name Z-A, oldest, newest, least recently pushed, most recently pushed
- [ ] Starred list sort order (`repo_starred_list_menu`): oldest, newest, least and most recently pushed
- [ ] Private and fork badges render
- [ ] Repository list from a user profile, an org profile, a starred list, a forks list

## 6. Issue lists (`issue_list_menu`, `issue_list_filter*`, `issue_list_sort`)
- [ ] My issues tabs: Created, Assigned, Mentioned, Participating
- [ ] Show closed toggle and Open Issues title
- [ ] Open an item from each tab
- [ ] Repository issue list opens
- [ ] Search in the list
- [ ] Remove filter
- [ ] Filter by milestone
- [ ] Filter by label
- [ ] Filter by assignee
- [ ] Filter by participating status
- [ ] Manage labels (collaborator menu)
- [ ] Manage milestones (collaborator menu)
- [ ] Sort: newest, oldest, most recently updated, least recently updated, most commented, least commented
- [ ] Same checks for the pull request list
- [ ] Create issue button
- [ ] Empty state

## 7. Issue detail (`issue_menu`, `issue_fragment_menu`, timeline)
- [ ] Header shows state, number, title, repo
- [ ] Body renders markdown, images, code blocks, task lists
- [ ] Labels, assignees, milestone, reactions shown
- [ ] Timeline events: comment, label change, assign, close, reopen, rename, cross reference, commit reference
- [ ] Menu: Close
- [ ] Menu: Reopen
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [ ] Menu: Copy number
- [ ] Edit button opens the issue editor and saves title, body, labels, assignee, milestone
- [ ] Comment box: type and send
- [ ] Comment box: advanced editor toggle
- [ ] Comment box: attach button
- [ ] Markdown buttons: bold, italic, strikethrough, h1, h2, h3, quote, code, link, image, bullet list, number list, task list, divider
- [ ] Comment overflow: Share, Add reaction, Edit, Delete, View in file
- [ ] Text selection menu: Quote
- [ ] Reaction picker: +1, -1, laugh, hooray, confused, heart, rocket, eyes, and the Loading state
- [ ] Reaction bar tap adds and removes a reaction
- [ ] Tap a user avatar and extra text in a comment
- [ ] Link inside markdown opens the right screen
- [ ] Rotate keeps the draft and scroll position

## 8. Create and edit issue, labels, milestones
- [ ] Create issue with title and body
- [ ] Create issue with labels, assignee, milestone chosen
- [ ] Validation error for an empty title
- [ ] Label list, create, edit, delete label
- [ ] Label color picker
- [ ] Milestone list: open and closed tabs
- [ ] Create milestone with due date
- [ ] Edit milestone, Close, Reopen, Delete (`edit_milestone_menu`)
- [ ] Milestone detail issue list

## 9. Pull request detail (`pullrequest_menu`, `pull_request_fragment_menu`)
- [ ] Conversation tab: header, branch info (head into base), status
- [ ] Commit status box: header tap and each row tap
- [ ] Commits tab, open a commit
- [ ] Files tab, open a file diff
- [ ] Menu: Merge (all merge styles GitLab offers)
- [ ] Menu: Review
- [ ] Menu: Reopen
- [ ] Menu: Close
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [ ] Menu: Copy number
- [ ] Delete branch after merge
- [ ] Comment, edit and delete a PR comment
- [ ] Create pull request
- [ ] Merged and closed states render correctly

## 10. Reviews and diffs (`review_menu`, `review_diff_hunk_menu`, diff viewer)
- [ ] Review list in the PR conversation, expand details
- [ ] Review comment tap and avatar tap
- [ ] Review overflow: Share, Open in browser
- [ ] Diff hunk menu: Share, View in file
- [ ] Diff viewer for a commit and for a PR file
- [ ] Add inline comment, reply, edit, delete
- [ ] Share a diff line (`file_line_menu`)
- [ ] Large and binary diffs do not crash
- [ ] Missing patch is filled from the `.diff` endpoint

## 11. Commits (`commit_menu`, `commit_comment_actions`, `commit_history_menu`)
- [ ] Commit list on the repo Commits tab, paginate
- [ ] Commit list for a file with Follow renames toggle
- [ ] Commit detail: message, author, stats, files changed
- [ ] Commit menu: Open in browser, Share
- [ ] Open a changed file from a commit
- [ ] Commit comments tab: add comment
- [ ] Commit comment actions: Share, Reply, Add comment, Add reaction, Edit, Delete
- [ ] Commit compare view between two refs
- [ ] Commit status box

## 12. Repository (`repo_menu`)
- [ ] About tab: description, language, counts
- [ ] Counts match the server for issues, pull requests, forks, stars, watchers
- [ ] Tap each count row opens its list
- [ ] README expand and collapse, relative links and images
- [ ] Overview row taps (owner, homepage)
- [ ] Menu: Select branch, switch to a tag and to another branch
- [ ] Menu: Share
- [ ] Menu: Open in browser
- [ ] Menu: Search code and open a result
- [ ] Menu: Download code as ZIP
- [ ] Menu: Bookmark and remove bookmark
- [ ] Star, unstar
- [ ] Watch, unwatch
- [ ] Fork
- [ ] Other information: Contributors, Collaborators, Releases, Wiki
- [ ] Wiki list and a wiki page (hidden or clear message when the wiki is off)
- [ ] Activity tab, open an item
- [ ] Empty repo
- [ ] Private repo
- [ ] Archived and mirror repo

## 13. Files (`file_viewer_menu`, content list)
- [ ] Folder browse and breadcrumbs, tap a breadcrumb
- [ ] Open a text file, syntax highlight
- [ ] Open a markdown file rendered
- [ ] Open an image file
- [ ] Open a large and a binary file
- [ ] File menu: Search in file, Open in browser, Share, Download, Wrap lines, Raw
- [ ] File history from the content list
- [ ] Line tap and share line
- [ ] Branch and tag picker keeps the path
- [ ] Gist file viewer: Open in browser, Download

## 14. Releases and tags
- [ ] Release list
- [ ] Release detail: title, tag, body, assets
- [ ] Tag text tap and avatar tap
- [ ] Menu: Open in browser, Add reaction
- [ ] Download an asset
- [ ] Release reaction picker

## 15. Users, orgs and social
- [ ] User profile: avatar, bio, counts, email, website
- [ ] Profile repository count matches the server
- [ ] Profile menu: Share, Open in browser, Bookmark
- [ ] Follow and unfollow
- [ ] Followers list, Following list
- [ ] Starred repos of a user
- [ ] Organizations of a user
- [ ] Org profile and org repos
- [ ] Org members list (`OrganizationMemberListFragment`), open a member
- [ ] Contributors list, open a contributor
- [ ] Collaborators list
- [ ] Stargazers and watchers lists
- [ ] Forks list
- [ ] User not found shows a readable error

## 16. Search (`search`)
- [ ] Type selector: repositories, users, issues, pull requests, code
- [ ] Submit search with keyboard and with the search icon
- [ ] Open a result of each type
- [ ] Empty result message
- [ ] Exact user and org name search
- [ ] Pagination
- [ ] Search history or suggestions if present

## 17. Bookmarks and stars
- [ ] Bookmarks tab lists bookmarks added from repo and user menus
- [ ] Open a bookmark
- [ ] Remove a bookmark (long press or swipe)
- [ ] Starred tab lists starred repos
- [ ] Starred sort order

## 18. Gists (`gist_menu`)
- [ ] My gists: Mine and Starred tabs
- [ ] Hidden or clear message because GitLab has no gists
- [ ] Gist detail menu: Star, Share (only if reachable)

## 19. Trending, timeline and blog
- [ ] Trending screen behaviour on GitLab
- [ ] Public timeline behaviour on GitLab
- [ ] Blog list menu: Open in browser (only if reachable)

## 20. Settings
- [ ] Start page: each option opens that screen at launch
- [ ] Use built-in browser on and off, then open a link in each mode
- [ ] Notifications switch on and off
- [ ] Theme dialog: Use system setting, Day, Night apply immediately
- [ ] Code view text size dialog lists five sizes
- [ ] Animated GIF loading dialog lists three options
- [ ] Sync interval dialog
- [ ] Text size and GIF choice take effect in a file view and a markdown image
- [ ] About dialog
- [ ] Open source components list
- [ ] Settings persist after restart
- [ ] Theme and dark mode follow the system

## 21. Dialogs and shared widgets
- [ ] Single-choice dialogs (ref picker, filter pickers) select and dismiss
- [ ] Markdown rendering: tables, nested lists, HTML, emoji, mentions, issue links
- [ ] Image viewer from markdown
- [ ] Web view find bar: find previous, find next
- [ ] Print menu
- [ ] Pull to refresh on every paged list
- [ ] Error view TRY AGAIN retries
- [ ] Empty view text on every list

## 22. Deep links and intents
- [ ] GitLab repo URL
- [ ] Issue URL
- [ ] PR URL
- [ ] Commit URL
- [ ] File URL on a branch
- [ ] User and org URL
- [ ] Release URL
- [ ] Share text into the app
- [ ] Open in browser from each screen that has the action

## 23. Robustness
- [ ] Rotate on a list, a detail view and a dialog
- [ ] Background and resume
- [ ] Airplane mode on a list shows a readable error, then TRY AGAIN recovers
- [ ] Slow network does not duplicate rows
- [ ] Release build behaves like debug (R8 does not strip the interceptor models)
- [ ] Back stack after deep link

- [ ] Re-check markdown rendering on issue body, issue comments, commit comments, release notes, review bodies (body_html is now synthesized)

- [ ] Issue menu has "Open in new window"; verify it
- [ ] Close dialog "Not planned" and "Duplicate" reasons (GitLab has only open/closed): hide them or accept
- [ ] Deep links on custom hosts cannot be registered in the manifest; confirm links to a custom host open in the browser
- [ ] Emoji shortcodes such as :bug: in issue and PR titles render as text, not emoji