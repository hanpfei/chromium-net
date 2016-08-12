#ifndef BRLAPI_INCLUDED_BRLDEFS
#define BRLAPI_INCLUDED_BRLDEFS

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/** do nothing */
#define BRL_CMD_NOOP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NOOP)
/** go up one line */
#define BRL_CMD_LNUP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_LNUP)
/** go down one line */
#define BRL_CMD_LNDN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_LNDN)
/** go up several lines */
#define BRL_CMD_WINUP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_WINUP)
/** go down several lines */
#define BRL_CMD_WINDN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_WINDN)
/** go up to nearest line with different content */
#define BRL_CMD_PRDIFLN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRDIFLN)
/** go down to nearest line with different content */
#define BRL_CMD_NXDIFLN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXDIFLN)
/** go up to nearest line with different highlighting */
#define BRL_CMD_ATTRUP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ATTRUP)
/** go down to nearest line with different highlighting */
#define BRL_CMD_ATTRDN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ATTRDN)
/** go to top line */
#define BRL_CMD_TOP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_TOP)
/** go to bottom line */
#define BRL_CMD_BOT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_BOT)
/** go to beginning of top line */
#define BRL_CMD_TOP_LEFT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_TOP_LEFT)
/** go to beginning of bottom line */
#define BRL_CMD_BOT_LEFT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_BOT_LEFT)
/** go up to last line of previous paragraph */
#define BRL_CMD_PRPGRPH (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRPGRPH)
/** go down to first line of next paragraph */
#define BRL_CMD_NXPGRPH (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXPGRPH)
/** go up to previous command prompt */
#define BRL_CMD_PRPROMPT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRPROMPT)
/** go down to next command prompt */
#define BRL_CMD_NXPROMPT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXPROMPT)
/** search backward for clipboard text */
#define BRL_CMD_PRSEARCH (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRSEARCH)
/** search forward for clipboard text */
#define BRL_CMD_NXSEARCH (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXSEARCH)
/** go left one character */
#define BRL_CMD_CHRLT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CHRLT)
/** go right one character */
#define BRL_CMD_CHRRT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CHRRT)
/** go left half a window */
#define BRL_CMD_HWINLT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_HWINLT)
/** go right half a window */
#define BRL_CMD_HWINRT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_HWINRT)
/** go left one window */
#define BRL_CMD_FWINLT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_FWINLT)
/** go right one window */
#define BRL_CMD_FWINRT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_FWINRT)
/** go left to nearest non-blank window */
#define BRL_CMD_FWINLTSKIP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_FWINLTSKIP)
/** go right to nearest non-blank window */
#define BRL_CMD_FWINRTSKIP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_FWINRTSKIP)
/** go to beginning of line */
#define BRL_CMD_LNBEG (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_LNBEG)
/** go to end of line */
#define BRL_CMD_LNEND (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_LNEND)
/** go to cursor */
#define BRL_CMD_HOME (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_HOME)
/** go back after cursor tracking */
#define BRL_CMD_BACK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_BACK)
/** go to cursor or go back after cursor tracking */
#define BRL_CMD_RETURN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_RETURN)
/** freeze/unfreeze screen image */
#define BRL_CMD_FREEZE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_FREEZE)
/** set display mode attributes/text */
#define BRL_CMD_DISPMD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_DISPMD)
/** set text style 6-dot/8-dot */
#define BRL_CMD_SIXDOTS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SIXDOTS)
/** set sliding window on/off */
#define BRL_CMD_SLIDEWIN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SLIDEWIN)
/** set skipping of lines with identical content on/off */
#define BRL_CMD_SKPIDLNS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SKPIDLNS)
/** set skipping of blank windows on/off */
#define BRL_CMD_SKPBLNKWINS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SKPBLNKWINS)
/** set cursor visibility on/off */
#define BRL_CMD_CSRVIS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRVIS)
/** set hidden cursor on/off */
#define BRL_CMD_CSRHIDE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRHIDE)
/** set cursor tracking on/off */
#define BRL_CMD_CSRTRK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRTRK)
/** set cursor style block/underline */
#define BRL_CMD_CSRSIZE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRSIZE)
/** set cursor blinking on/off */
#define BRL_CMD_CSRBLINK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRBLINK)
/** set attribute underlining on/off */
#define BRL_CMD_ATTRVIS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ATTRVIS)
/** set attribute blinking on/off */
#define BRL_CMD_ATTRBLINK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ATTRBLINK)
/** set capital letter blinking on/off */
#define BRL_CMD_CAPBLINK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CAPBLINK)
/** set alert tunes on/off */
#define BRL_CMD_TUNES (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_TUNES)
/** set autorepeat on/off */
#define BRL_CMD_AUTOREPEAT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_AUTOREPEAT)
/** set autospeak on/off */
#define BRL_CMD_AUTOSPEAK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_AUTOSPEAK)
/** enter/leave help display */
#define BRL_CMD_HELP (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_HELP)
/** enter/leave status display */
#define BRL_CMD_INFO (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_INFO)
/** enter/leave command learn mode */
#define BRL_CMD_LEARN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_LEARN)
/** enter/leave preferences menu */
#define BRL_CMD_PREFMENU (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PREFMENU)
/** save preferences to disk */
#define BRL_CMD_PREFSAVE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PREFSAVE)
/** restore preferences from disk */
#define BRL_CMD_PREFLOAD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PREFLOAD)
/** go to first item */
#define BRL_CMD_MENU_FIRST_ITEM (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_FIRST_ITEM)
/** go to last item */
#define BRL_CMD_MENU_LAST_ITEM (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_LAST_ITEM)
/** go to previous item */
#define BRL_CMD_MENU_PREV_ITEM (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_PREV_ITEM)
/** go to next item */
#define BRL_CMD_MENU_NEXT_ITEM (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_NEXT_ITEM)
/** select previous choice */
#define BRL_CMD_MENU_PREV_SETTING (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_PREV_SETTING)
/** select next choice */
#define BRL_CMD_MENU_NEXT_SETTING (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_NEXT_SETTING)
/** stop speaking */
#define BRL_CMD_MUTE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MUTE)
/** go to current speech position */
#define BRL_CMD_SPKHOME (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPKHOME)
/** speak current line */
#define BRL_CMD_SAY_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_LINE)
/** speak from top of screen through current line */
#define BRL_CMD_SAY_ABOVE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_ABOVE)
/** speak from current line through bottom of screen */
#define BRL_CMD_SAY_BELOW (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_BELOW)
/** decrease speech rate */
#define BRL_CMD_SAY_SLOWER (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_SLOWER)
/** increase speech rate */
#define BRL_CMD_SAY_FASTER (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_FASTER)
/** decrease speech volume */
#define BRL_CMD_SAY_SOFTER (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_SOFTER)
/** increase speech volume */
#define BRL_CMD_SAY_LOUDER (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SAY_LOUDER)
/** switch to previous virtual terminal */
#define BRL_CMD_SWITCHVT_PREV (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SWITCHVT_PREV)
/** switch to next virtual terminal */
#define BRL_CMD_SWITCHVT_NEXT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SWITCHVT_NEXT)
/** bring cursor to line */
#define BRL_CMD_CSRJMP_VERT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CSRJMP_VERT)
/** insert clipboard text at cursor */
#define BRL_CMD_PASTE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PASTE)
/** restart braille driver */
#define BRL_CMD_RESTARTBRL (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_RESTARTBRL)
/** restart speech driver */
#define BRL_CMD_RESTARTSPEECH (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_RESTARTSPEECH)
/** braille display temporarily unavailable */
#define BRL_CMD_OFFLINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_OFFLINE)
/** add shift modifier to next typed character or emulated key */
#define BRL_CMD_SHIFT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SHIFT)
/** add upper modifier to next typed character or emulated key */
#define BRL_CMD_UPPER (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_UPPER)
/** add control modifier to next typed character or emulated key */
#define BRL_CMD_CONTROL (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CONTROL)
/** add meta modifier to next typed character or emulated key */
#define BRL_CMD_META (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_META)
/** show the current date and time */
#define BRL_CMD_TIME (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_TIME)
/** go to previous menu level */
#define BRL_CMD_MENU_PREV_LEVEL (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_MENU_PREV_LEVEL)
/** set autospeak selected line on/off */
#define BRL_CMD_ASPK_SEL_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_SEL_LINE)
/** set autospeak selected character on/off */
#define BRL_CMD_ASPK_SEL_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_SEL_CHAR)
/** set autospeak inserted characters on/off */
#define BRL_CMD_ASPK_INS_CHARS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_INS_CHARS)
/** set autospeak deleted characters on/off */
#define BRL_CMD_ASPK_DEL_CHARS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_DEL_CHARS)
/** set autospeak replaced characters on/off */
#define BRL_CMD_ASPK_REP_CHARS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_REP_CHARS)
/** set autospeak completed words on/off */
#define BRL_CMD_ASPK_CMP_WORDS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ASPK_CMP_WORDS)
/** speak current character */
#define BRL_CMD_SPEAK_CURR_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_CURR_CHAR)
/** go to and speak previous character */
#define BRL_CMD_SPEAK_PREV_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_PREV_CHAR)
/** go to and speak next character */
#define BRL_CMD_SPEAK_NEXT_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_NEXT_CHAR)
/** speak current word */
#define BRL_CMD_SPEAK_CURR_WORD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_CURR_WORD)
/** go to and speak previous word */
#define BRL_CMD_SPEAK_PREV_WORD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_PREV_WORD)
/** go to and speak next word */
#define BRL_CMD_SPEAK_NEXT_WORD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_NEXT_WORD)
/** speak current line */
#define BRL_CMD_SPEAK_CURR_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_CURR_LINE)
/** go to and speak previous line */
#define BRL_CMD_SPEAK_PREV_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_PREV_LINE)
/** go to and speak next line */
#define BRL_CMD_SPEAK_NEXT_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_NEXT_LINE)
/** go to and speak first non-blank character on line */
#define BRL_CMD_SPEAK_FRST_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_FRST_CHAR)
/** go to and speak last non-blank character on line */
#define BRL_CMD_SPEAK_LAST_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_LAST_CHAR)
/** go to and speak first non-blank line on screen */
#define BRL_CMD_SPEAK_FRST_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_FRST_LINE)
/** go to and speak last non-blank line on screen */
#define BRL_CMD_SPEAK_LAST_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_LAST_LINE)
/** describe current character */
#define BRL_CMD_DESC_CURR_CHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_DESC_CURR_CHAR)
/** spell current word */
#define BRL_CMD_SPELL_CURR_WORD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPELL_CURR_WORD)
/** bring cursor to speech location */
#define BRL_CMD_ROUTE_CURR_LOCN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ROUTE_CURR_LOCN)
/** speak speech location */
#define BRL_CMD_SPEAK_CURR_LOCN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SPEAK_CURR_LOCN)
/** set speech location visibility on/off */
#define BRL_CMD_SHOW_CURR_LOCN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SHOW_CURR_LOCN)
/** enable feature */
#define BRL_FLG_TOGGLE_ON BRLAPI_KEY_FLG_TOGGLE_ON
/** disable feature */
#define BRL_FLG_TOGGLE_OFF BRLAPI_KEY_FLG_TOGGLE_OFF
/** mask for all toggle flags */
#define BRL_FLG_TOGGLE_MASK BRLAPI_KEY_FLG_TOGGLE_MASK
/** bring cursor into window after function */
#define BRL_FLG_MOTION_ROUTE BRLAPI_KEY_FLG_MOTION_ROUTE
/** execute command on key press */
#define BRL_FLG_REPEAT_INITIAL BRLAPI_KEY_FLG_REPEAT_INITIAL
/** wait before repeating */
#define BRL_FLG_REPEAT_DELAY BRLAPI_KEY_FLG_REPEAT_DELAY
/** mask for all repeat flags */
#define BRL_FLG_REPEAT_MASK BRLAPI_KEY_FLG_REPEAT_MASK
/** bring cursor to character */
#define BRL_BLK_ROUTE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_ROUTE)
/** start new clipboard at character */
#define BRL_BLK_CLIP_NEW (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CLIP_NEW)
/** deprecated definition of CLIP_NEW - start new clipboard at character */
#define BRL_BLK_CUTBEGIN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CUTBEGIN)
/** append to clipboard from character */
#define BRL_BLK_CLIP_ADD (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CLIP_ADD)
/** deprecated definition of CLIP_ADD - append to clipboard from character */
#define BRL_BLK_CUTAPPEND (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CUTAPPEND)
/** rectangular copy to character */
#define BRL_BLK_COPY_RECT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_COPY_RECT)
/** deprecated definition of COPY_RECT - rectangular copy to character */
#define BRL_BLK_CUTRECT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CUTRECT)
/** linear copy to character */
#define BRL_BLK_COPY_LINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_COPY_LINE)
/** deprecated definition of COPY_LINE - linear copy to character */
#define BRL_BLK_CUTLINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CUTLINE)
/** switch to virtual terminal */
#define BRL_BLK_SWITCHVT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SWITCHVT)
/** go up to nearest line with less indent than character */
#define BRL_BLK_PRINDENT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRINDENT)
/** go down to nearest line with less indent than character */
#define BRL_BLK_NXINDENT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXINDENT)
/** describe character */
#define BRL_BLK_DESCCHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_DESCCHAR)
/** place left end of window at character */
#define BRL_BLK_SETLEFT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SETLEFT)
/** remember current window position */
#define BRL_BLK_SETMARK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_SETMARK)
/** go to remembered window position */
#define BRL_BLK_GOTOMARK (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_GOTOMARK)
/** go to selected line */
#define BRL_BLK_GOTOLINE (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_GOTOLINE)
/** scale arg=0X00-0XFF to screen height */
#define BRL_FLG_LINE_SCALED BRLAPI_KEY_FLG_LINE_SCALED
/** go to beginning of line */
#define BRL_FLG_LINE_TOLEFT BRLAPI_KEY_FLG_LINE_TOLEFT
/** go up to nearest line with different character */
#define BRL_BLK_PRDIFCHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PRDIFCHAR)
/** go down to nearest line with different character */
#define BRL_BLK_NXDIFCHAR (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_NXDIFCHAR)
/** copy characters to clipboard */
#define BRL_BLK_CLIP_COPY (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CLIP_COPY)
/** deprecated definition of CLIP_COPY - copy characters to clipboard */
#define BRL_BLK_COPYCHARS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_COPYCHARS)
/** append characters to clipboard */
#define BRL_BLK_CLIP_APPEND (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CLIP_APPEND)
/** deprecated definition of CLIP_APPEND - append characters to clipboard */
#define BRL_BLK_APNDCHARS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_APNDCHARS)
/** put random password into clipboard */
#define BRL_BLK_PWGEN (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PWGEN)
/** emulate special key */
#define BRL_KEY_PASSKEY (BRLAPI_KEY_TYPE_SYM | 0XFF00)
/** enter key */
#define BRL_KEY_ENTER (BRLAPI_KEY_SYM_LINEFEED & 0XFF)
/** tab key */
#define BRL_KEY_TAB (BRLAPI_KEY_SYM_TAB & 0XFF)
/** backspace key */
#define BRL_KEY_BACKSPACE (BRLAPI_KEY_SYM_BACKSPACE & 0XFF)
/** escape key */
#define BRL_KEY_ESCAPE (BRLAPI_KEY_SYM_ESCAPE & 0XFF)
/** cursor-left key */
#define BRL_KEY_CURSOR_LEFT (BRLAPI_KEY_SYM_LEFT & 0XFF)
/** cursor-right key */
#define BRL_KEY_CURSOR_RIGHT (BRLAPI_KEY_SYM_RIGHT & 0XFF)
/** cursor-up key */
#define BRL_KEY_CURSOR_UP (BRLAPI_KEY_SYM_UP & 0XFF)
/** cursor-down key */
#define BRL_KEY_CURSOR_DOWN (BRLAPI_KEY_SYM_DOWN & 0XFF)
/** page-up key */
#define BRL_KEY_PAGE_UP (BRLAPI_KEY_SYM_PAGE_UP & 0XFF)
/** page-down key */
#define BRL_KEY_PAGE_DOWN (BRLAPI_KEY_SYM_PAGE_DOWN & 0XFF)
/** home key */
#define BRL_KEY_HOME (BRLAPI_KEY_SYM_HOME & 0XFF)
/** end key */
#define BRL_KEY_END (BRLAPI_KEY_SYM_END & 0XFF)
/** insert key */
#define BRL_KEY_INSERT (BRLAPI_KEY_SYM_INSERT & 0XFF)
/** delete key */
#define BRL_KEY_DELETE (BRLAPI_KEY_SYM_DELETE & 0XFF)
/** function key */
#define BRL_KEY_FUNCTION (BRLAPI_KEY_SYM_FUNCTION & 0XFF)
/** type unicode character */
#define BRL_KEY_PASSCHAR (BRLAPI_KEY_TYPE_SYM | 0X0000)
/** type braille character */
#define BRL_BLK_PASSDOTS (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PASSDOTS)
/** shift key pressed */
#define BRL_FLG_CHAR_SHIFT BRLAPI_KEY_FLG_SHIFT
/** convert to uppercase */
#define BRL_FLG_CHAR_UPPER BRLAPI_KEY_FLG_UPPER
/** control key pressed */
#define BRL_FLG_CHAR_CONTROL BRLAPI_KEY_FLG_CONTROL
/** meta key pressed */
#define BRL_FLG_CHAR_META BRLAPI_KEY_FLG_META
/** upper-left dot of standard braille cell */
#define BRL_DOT1 BRLAPI_DOT1
/** middle-left dot of standard braille cell */
#define BRL_DOT2 BRLAPI_DOT2
/** lower-left dot of standard braille cell */
#define BRL_DOT3 BRLAPI_DOT3
/** upper-right dot of standard braille cell */
#define BRL_DOT4 BRLAPI_DOT4
/** middle-right dot of standard braille cell */
#define BRL_DOT5 BRLAPI_DOT5
/** lower-right dot of standard braille cell */
#define BRL_DOT6 BRLAPI_DOT6
/** lower-left dot of computer braille cell */
#define BRL_DOT7 BRLAPI_DOT7
/** lower-right dot of computer braille cell */
#define BRL_DOT8 BRLAPI_DOT8
/** space key pressed */
#define BRL_DOTC BRLAPI_DOTC
/** AT (set 2) keyboard scan code */
#define BRL_BLK_PASSAT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PASSAT)
/** XT (set 1) keyboard scan code */
#define BRL_BLK_PASSXT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PASSXT)
/** PS/2 (set 3) keyboard scan code */
#define BRL_BLK_PASSPS2 (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_PASSPS2)
/** it is a release scan code */
#define BRL_FLG_KBD_RELEASE BRLAPI_KEY_FLG_KBD_RELEASE
/** it is an emulation 0 scan code */
#define BRL_FLG_KBD_EMUL0 BRLAPI_KEY_FLG_KBD_EMUL0
/** it is an emulation 1 scan code */
#define BRL_FLG_KBD_EMUL1 BRLAPI_KEY_FLG_KBD_EMUL1
/** switch to command context */
#define BRL_BLK_CONTEXT (BRLAPI_KEY_TYPE_CMD | BRLAPI_KEY_CMD_CONTEXT)
/** mask for command type */
#define BRL_MSK_BLK (BRLAPI_KEY_TYPE_MASK | BRLAPI_KEY_CMD_BLK_MASK)
/** mask for command value/argument */
#define BRL_MSK_ARG BRLAPI_KEY_CMD_ARG_MASK
/** mask for command flags */
#define BRL_MSK_FLG BRLAPI_KEY_FLAGS_MASK
/** mask for command */
#define BRL_MSK_CMD (BRL_MSK_BLK | BRL_MSK_ARG)

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* BRLAPI_INCLUDED_BRLDEFS */
