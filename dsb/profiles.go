package main

import "strings"

// Validate before building a client: invalid selectors must not launch a backend.
func validateProfileArgs(args *Args) error {
	for _, field := range []struct {
		name  string
		value *string
	}{
		{"--profile", args.Profile}, {"--user-data-dir", args.UserDataDir},
		{"--profile-directory", args.ProfileDirectory}, {"--name", args.ProfileName},
		{"--source-user-data-dir", args.SourceUserDataDir}, {"--source-profile-directory", args.SourceProfileDirectory},
	} {
		if field.value != nil && strings.TrimSpace(*field.value) == "" {
			return usageErrorf("%s must not be empty", field.name)
		}
	}
	if args.Command == "start" {
		if args.Profile != nil && (args.UserDataDir != nil || args.ProfileDirectory != nil) {
			return usageErrorf("--profile cannot be combined with --user-data-dir or --profile-directory")
		}
		if args.ProfileDirectory != nil && args.UserDataDir == nil {
			return usageErrorf("--profile-directory requires --user-data-dir")
		}
		if hasProfileSelection(args) {
			switch strings.ToLower(strings.TrimSpace(derefOr(args.Browser, ""))) {
			case "", "auto", "chrome", "google-chrome":
			default:
				return usageErrorf("explicit profile selection requires Chrome; use --browser chrome")
			}
		}
	}
	if args.Command == "profile" {
		switch derefOr(args.Action, "") {
		case "list":
			if args.ProfileName != nil || args.SourceUserDataDir != nil || args.SourceProfileDirectory != nil {
				return usageErrorf("profile list does not accept clone options")
			}
		case "clone":
			if args.ProfileName == nil || args.SourceUserDataDir == nil {
				return usageErrorf("profile clone requires --name and --source-user-data-dir")
			}
		default:
			return usageErrorf("profile action must be list or clone")
		}
	}
	return nil
}

func hasProfileSelection(args *Args) bool {
	return args.Profile != nil || args.UserDataDir != nil || args.ProfileDirectory != nil
}

// Old servers can ignore unknown start parameters. Refuse before sending start.
func startWithProfile(client CommandAPI, args *Args, browser string) (*Response, error) {
	if err := validateProfileArgs(args); err != nil {
		return nil, err
	}
	if !hasProfileSelection(args) {
		return client.Start(browser, !args.Headful, nil)
	}
	methods, err := client.Methods()
	if err != nil {
		return nil, err
	}
	if !methods.Ok() {
		return methods, nil
	}
	supported := false
	for _, method := range asArray(objGet(methods.Data(), "methods")) {
		if method == "list_profiles" {
			supported = true
		}
	}
	if !supported {
		return nil, usageErrorf("backend does not advertise list_profiles; explicit profile selection requires an updated backend. No start request was sent")
	}
	params := ObjOf("headless", !args.Headful, "browser", "chrome")
	if args.Profile != nil {
		params.Set("profile", *args.Profile)
	}
	if args.UserDataDir != nil {
		params.Set("userDataDir", *args.UserDataDir)
	}
	if args.ProfileDirectory != nil {
		params.Set("profileDirectory", *args.ProfileDirectory)
	}
	return client.Command("start", params, nil, 0, "start")
}

func cmdProfiles(client CommandAPI, args *Args, out *Printer) (int, error) {
	if err := validateProfileArgs(args); err != nil {
		return 0, err
	}
	method := "list_profiles"
	params := NewObj()
	if args.Command == "profile" && derefOr(args.Action, "") == "clone" {
		method = "clone_profile"
		params.Set("name", *args.ProfileName)
		params.Set("sourceUserDataDir", *args.SourceUserDataDir)
		params.Set("sourceProfileDirectory", derefOr(args.SourceProfileDirectory, "Default"))
	}
	response, err := client.Command(method, params, nil, 0, method)
	if err != nil {
		return 0, err
	}
	value, handled, err := prepare(response, args, out)
	if err != nil {
		return 0, err
	}
	if !handled {
		out.JSON(value)
	}
	return verdict(response), nil
}
