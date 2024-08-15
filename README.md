In order to run these scripts, grab a personal access token in your github settings.

```
❯ ./run.sh github --help
Usage: metrics github [<options>] <command> [<args>]...

Options:
  -h, --help  Show this message and exit

Commands:
  config
  rate-limit
  team-pr-stats
  user-pr-stats
```

```
~/Development/team-github-metrics main !1 ❯ ./run.sh github team-pr-stats --help
Usage: metrics github team-pr-stats [<options>]

  Get a report of PR activity by user for a given team

Options:
  --org=<text>     The primary github organization to filter activity by
  --team=<text>    Will look at activity by all members of the team
  --author=<text>  Will look at activity only by this github user
  --days=<int>     Look at the last N days of activity
  --full-details   Fetch more detailed PR data (slower)
  -h, --help       Show this message and exit
```

```
~/Development/team-github-metrics main !1 ❯ ./run.sh github user-pr-stats --help
Usage: metrics github user-pr-stats [<options>]

  Get a report of PR activity for a given user

Options:
  --org=<text>     The primary github organization to filter activity by
  --team=<text>    Will look at activity by all members of the team
  --author=<text>  Will look at activity only by this github user
  --days=<int>     Look at the last N days of activity
  --full-details   Fetch more detailed PR data (slower)
  -h, --help       Show this message and exit
```
